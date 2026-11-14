# LDAP 주소 여럿 — DC 장애 넘김 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** app-ldap 이 LDAP 주소 목록(`ldap.urls`)을 받아, 앞 DC 가 접속·바인드에서 실패하면 JNDI 가 다음 주소로 넘기고, 재시도는 다음 주소부터 읽으며, 첫 주소가 아닌 곳에서 읽으면 WARN 을 남기게 한다. 비율만 말하는 코드 주석 6곳과 문서를 고친다.

**Architecture:** `LdapProperties.urls` → `LdapContextSource.setUrls` 로 JNDI 표준 주소 목록을 켠다(인증 실패는 JNDI 가 넘기지 않는다). 커넥션이 지나는 `LdapTemplates.한_커넥션에서` 가 붙은 주소(커넥션 환경의 `java.naming.provider.url`)를 보고 넘겼으면 WARN 한다. `LdapDirectorySnapshotSource` 는 시작 주소별 템플릿 목록을 받아 시도 k 를 `k % n` 번째 템플릿으로 읽는다. 상태 점검은 지금처럼 목록 하나로 바인드 하나다.

**Tech Stack:** Java 17, Spring Boot 속성 바인딩, Spring LDAP 3.3.8(`LdapContextSource`), JNDI(JDK 17), Reactor, JUnit 5 + AssertJ, UnboundID 임베디드 LDAP(`InMemoryDirectoryServer`·`InMemoryOperationInterceptor`), logback `ListAppender`, Gradle 멀티 모듈(connector-ldap, app-ldap, core).

**Spec:** `docs/superpowers/specs/2026-11-14-ldap-dc-failover-design.md`

## Global Constraints

- 결정은 설계 §2 의 F1~F10 이다. 설계와 이 계획이 어긋나면 설계가 이긴다.
- 설정 키는 `ldap.urls`(목록, 기본 `[ldap://localhost:1389]`)다. `ldap.url` 은 없앤다. 목록 순서가 선호 순서다(F1).
- 접속 단계의 넘김은 JNDI 에 맡긴다 — `LdapContextSource.setUrls`(F2). 인증 실패(`javax.naming.AuthenticationException`, 49)는 넘기지 않는다. 앱이 그것을 바꾸지 않는다(F3).
- 기동 검사(F4): 빈 목록, 빈 항목, 스킴이 `ldap`·`ldaps` 가 아님(대소문자 무시), 호스트(권한) 없음, `/` 말고 경로·질의·조각이 붙음 → 멈춘다. `ldap.require-tls` 면 모든 주소가 `ldaps` 여야 한다. 오류 문구에 비밀번호를 싣지 않는다.
- 상태 점검은 목록 하나로 바인드 하나, 2초 상한, 인증 실패 뒤 30분 쉼 — 지금 그대로(F5). 주소마다 따로 바인드하지 않는다.
- 재시도 k 번째(0 부터)는 목록을 k 만큼 돌린 순서로 그림 전체를 읽는다. 다음 회차는 다시 설정 순서다. 재시도 규칙(무엇을 재시도하는가)은 그대로다(F6).
- WARN(F7): (a) 붙은 주소가 그 시도의 첫 주소가 아닌 커넥션마다 한 줄, (b) 다음 주소부터 읽는 재시도마다 시작 주소를 적은 한 줄. 새 지표 없음.
- 주소 하나면 지금과 같은 판정이고 WARN 이 없다(F8).
- 시간 임계치를 단언하지 않는다(온보딩 14 "벽시계 단언"). 로그에 `externalId` 를 싣지 않는다.
- 서브에이전트는 모듈 시험만 돌린다: `./gradlew :connector-ldap:test --tests <클래스>`·`./gradlew :app-ldap:test --tests <클래스>`·`./gradlew :core:test`. Gradle 은 한 번에 하나만 돈다. 전체 `cleanTest test`·`cleanScaleTest scaleTest` 는 메인 세션이 머지 전에 돌린다.
- git 은 꾸밈 없는 단일 명령으로 쓴다(`&&`·`$(...)` 금지 — 작업 트리 가드가 거절한다). `git stash` 금지.
- 커밋 메시지는 `<종류>: <한국어 요약> (DC 넘김)` 이고, 끝 줄은 `Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>` 이다.
- 식별자·자바독·주석은 이 저장소처럼 한국어로 쓴다. 자바독에 근거 설계를 `설계 2026-11-14 F<n>` 꼴로 적는다.

## Review Focus

1. **환경 변수로 준 목록에 쉼표 뒤 공백**(`LDAP_URLS=ldaps://a:636, ldaps://b:636`) — 두 주소가 깨끗하게 들어가고 기동한다. → Task 1 의 `쉼표로_이은_문자열과_목록이_같은_주소_목록이_된다`.
2. **대문자 스킴**(`LDAPS://dc1:636`) — 스킴은 대소문자를 가리지 않는다(RFC 3986). 받고, `require-tls` 도 통과한다. → Task 1 의 기동 검사 시험.
3. **IPv6 주소와 밑줄이 든 호스트 이름**(`ldap://[::1]:389`, `ldap://dc_1.corp:389`) — 받는다. `java.net.URI` 는 밑줄 든 호스트에 `getHost()` 가 `null` 이라, 호스트 검사를 그것으로 하면 맞는 주소를 거절한다. → Task 1 의 기동 검사 시험.
4. **주소 하나인 배포** — WARN 이 없고 상태 점검 세부에 그 주소가 붙은 주소로 보인다. → Task 2 의 `주소가_하나면_WARN_이_없다`·상태 점검 시험.
5. **모든 주소가 내려감** — 재시도가 돌며 모두 실패하고, 마지막 실패를 그대로 던진다(지금 규칙). → Task 3 의 `모든_주소가_거절하면_재시도를_다_쓰고_마지막_실패를_던진다`.

---

### Task 1: 주소 목록·기동 검사·JNDI 목록 (F1~F4, F8)

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapProperties.java` — `url` → `urls`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapConfig.java` — `설정을_검사한다`, `ldapContextSource`, 자바독
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/LdapHealthIndicator.java:89` — 세부 `"url"` → `"urls"`(Task 2 가 붙은 주소를 더한다)
- Modify: `app-ldap/src/main/resources/application.yml:39` (`url:` → `urls:` 목록), `app-ldap/src/main/resources/application-prod.yml:37` 주석
- Create: `connector-ldap/src/testFixtures/java/dev/starryeye/organization/ldap/fixture/SilentLdapServer.java`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapFailoverTest.java`
- Modify(시험의 주소 넣기): `connector-ldap/src/test/.../EmbeddedLdapSupport.java:112`, `LdapTimeoutTest.java:65-81`(침묵하는 서버를 픽스처로), `LdapReadTimeoutDuringPagingTest.java:64`, `LdapConfigValidationTest.java:102,105,119`, `app-ldap/src/test/.../LdapHealthIndicatorTest.java:37`, app-ldap 시험의 `registry.add("ldap.url", …)` 모두(15개 파일)
- Test: `LdapConfigValidationTest`, `LdapFailoverTest`, 새 바인딩 시험(아래)

**Interfaces:**
- Produces:
  - `LdapProperties#getUrls(): List<String>` / `setUrls(List<String>)` — 기본 `List.of("ldap://localhost:1389")`.
  - `SilentLdapServer implements AutoCloseable` — `static SilentLdapServer 연다()`(127.0.0.1 의 빈 포트, 접속을 받아 두기만 하고 아무것도 쓰지 않는 데몬 스레드), `String 접속_주소()`(`ldap://127.0.0.1:<포트>`), `close()`. 그리고 `static String 닫힌_주소()` — 빈 포트를 잡았다 놓아 연결을 거절하는 `ldap://127.0.0.1:<포트>`.
  - `LdapFailoverTest` — Task 2·3 이 시험을 더한다. 이 과제에서 두 임베디드 서버를 띄우는 도우미를 둔다: `private static InMemoryDirectoryServer 서버(String 비밀번호, InMemoryOperationInterceptor... 가로채기)` — `EmbeddedLdapSupport` 와 같은 BASE_DN·BIND_DN, `EmbeddedLdapListener.리스너("test")`, 직원 하나·조직 하나의 작은 LDIF(이름 기반 식별 속성, `EmbeddedLdapSupport.이름기반()`). 바인드 수를 세는 가로채기 `private static final class 바인드를_센다 extends InMemoryOperationInterceptor`(`processSimpleBindRequest` 에서 `AtomicInteger` 를 올린다).

- [ ] **Step 1: 시험의 주소 넣기를 목록으로 바꾸고 침묵하는 서버를 픽스처로 꺼낸다(아직 컴파일 안 됨)**

`LdapProperties.setUrl(x)` 을 부르는 곳을 `setUrls(List.of(x))` 로. `LdapTimeoutTest.인증_응답이_없으면_연결_타임아웃으로_끝난다` 의 `ServerSocket`·받기 스레드를 `SilentLdapServer.연다()` 로 바꾼다(동작 같음). app-ldap 시험의 `registry.add("ldap.url", …)` 는 키만 `"ldap.urls"` 로(값은 문자열 하나 그대로 — 부트가 원소 하나짜리 목록으로 바인딩한다). 스프링 `LdapContextSource.setUrl` 을 부르는 곳(`EmbeddedLdapSupport` 약 91행 등)은 그대로 둔다.

- [ ] **Step 2: 기동 검사 시험을 고치고 더한다 — `LdapConfigValidationTest`**

지금 `TLS_를_요구하면_ldaps_만_받는다` 의 문구 단언을 아래 새 문구로 바꾼다. 더하는 시험:

```java
@Test
@DisplayName("주소 목록이 비었거나 빈 항목이 있으면 멈춘다")
void 주소_목록이_비었거나_빈_항목이_있으면_멈춘다() {
    var 빈_목록 = 설정();
    빈_목록.setUrls(List.of());
    var 빈_항목 = 설정();
    빈_항목.setUrls(List.of("ldap://dc1:389", " "));

    assertThatThrownBy(() -> LdapConfig.설정을_검사한다(빈_목록))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ldap.urls 가 비었다 — LDAP 주소를 하나 이상 준다");
    assertThatThrownBy(() -> LdapConfig.설정을_검사한다(빈_항목))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ldap.urls 의 주소는 ldap://호스트[:포트] 또는 ldaps://호스트[:포트] 다 — 검색 베이스는 ldap.base-dn 에 준다(받은 값:  )");
}
```

| 시험 | 단언 |
|---|---|
| `주소_모양이_틀리면_멈춘다` | `http://dc1:389`, `ldap://`(호스트 없음), `ldap://dc1:389/dc=example,dc=com`, `ldap://dc1:389?x`, `dc1:389` 각각 위 모양 문구(`받은 값: <그 주소>`)로 멈춘다 |
| `주소_모양이_맞으면_받는다` | `ldap://dc1`, `ldap://dc1:389/`, `LDAPS://DC1:636`, `ldap://[::1]:389`, `ldap://dc_1.corp:389` 를 담은 목록은 예외 없음 |
| `TLS_를_요구하면_모든_주소가_ldaps_여야_한다` | `require-tls` + `[ldaps://dc1:636, ldap://dc2:389]` → `IllegalStateException`, 문구 `"ldap.require-tls 인데 ldap.urls 에 ldaps:// 가 아닌 주소가 있다 — StartTLS 는 지원하지 않는다(받은 값: ldap://dc2:389)"`. `[ldaps://dc1:636, LDAPS://dc2:636]` 은 예외 없음 |

검사 순서: 목록 비었는지 → 주소마다 모양 → `require-tls`. 깨뜨릴 변경(주석으로 적는다): 검사 빼기, 첫 주소만 보기.

- [ ] **Step 3: 바인딩 시험을 더한다 — `connector-ldap/src/test/.../LdapPropertiesBindingTest.java`(새 파일)**

`Binder` + `MapConfigurationPropertySource` 로 `ldap` 을 `Bindable.ofInstance(new LdapProperties())` 에 바인딩한다.

| 시험 | 단언 |
|---|---|
| `쉼표로_이은_문자열과_목록이_같은_주소_목록이_된다` | `ldap.urls = "ldaps://a:636, ldaps://b:636"` 과 `ldap.urls[0] = ldaps://a:636`·`ldap.urls[1] = ldaps://b:636` 이 같은 `getUrls()`(`[ldaps://a:636, ldaps://b:636]`) |
| `주소를_주지_않으면_로컬_기본값이다` | `getUrls()` 가 `[ldap://localhost:1389]` |

- [ ] **Step 4: 넘김 시험을 쓴다 — `LdapFailoverTest`**

`LdapConfig` 로 운영과 같이 만든다: `contextSource = new LdapConfig().ldapContextSource(properties); contextSource.afterPropertiesSet(); source = config.ldapDirectorySnapshotSource(config.ldapTemplate(contextSource), config.ldapMappingStrategy(properties, Clock.systemUTC()), properties)`. 설정은 `EmbeddedLdapSupport.이름기반()` + 바인드 자격 + 짧은 재시도 대기(`retryMinBackoff` 10ms, `retryMaxBackoff` 50ms). 회차 읽기는 `source.fetchAll().block(Duration.ofSeconds(30))`.

| 시험 | 준비 | 단언 |
|---|---|---|
| `주소_목록이_컨텍스트_소스에_순서대로_실린다` | `urls = [ldap://dc1:389, ldaps://dc2:636]` | `contextSource.getUrls()` 가 `{"ldap://dc1:389", "ldaps://dc2:636"}` 순서 그대로 |
| `첫_주소가_연결을_거절하면_둘째_주소에서_그림을_읽는다` | `[SilentLdapServer.닫힌_주소(), 서버 B]` | 그림의 직원·조직이 B 의 LDIF 와 같다 |
| `첫_주소가_접속만_받고_답하지_않으면_접속_한도_뒤_둘째에서_읽는다` | `[SilentLdapServer.연다().접속_주소(), 서버 B]`, `connectTimeout` 500ms | 그림을 읽는다 |
| `첫_주소가_인증을_거절하면_둘째로_넘기지_않는다` | 서버 A(비밀번호 `"다른비밀번호"`, 바인드를 센다), 서버 B(맞는 비밀번호, 바인드를 센다), `maxRetries` 3 | `fetchAll().block()` 이 `org.springframework.ldap.AuthenticationException` 으로 끝난다. A 가 받은 바인드 1(재시도 없음), B 가 받은 바인드 0 |

- [ ] **Step 5: 시험이 실패하는지 본다**

Run: `./gradlew :connector-ldap:test --tests "dev.starryeye.organization.ldap.LdapConfigValidationTest" --tests "dev.starryeye.organization.ldap.LdapPropertiesBindingTest" --tests "dev.starryeye.organization.ldap.LdapFailoverTest"`
Expected: 컴파일 실패 — `setUrls`·`getUrls` 가 없다.

- [ ] **Step 6: `LdapProperties.urls`, `LdapConfig.설정을_검사한다`, `LdapConfig.ldapContextSource` 를 바꾼다**

- `LdapProperties`: `private List<String> urls = List.of("ldap://localhost:1389");` 와 자바독(목록 순서가 선호 순서, 새 커넥션마다 JNDI 가 앞에서부터 시도, 인증 실패는 넘기지 않음, 검색 베이스는 `base-dn` — 설계 2026-11-14 F1~F4).
- `설정을_검사한다`: 주소 모양은 `java.net.URI` 로 본다 — 앞뒤 공백을 걷은 값이 비었거나 `URISyntaxException`, 스킴이 `ldap`·`ldaps`(대소문자 무시)가 아님, `getRawAuthority()` 가 `null` 이거나 빔(`getHost()` 는 쓰지 않는다 — 밑줄 든 호스트에서 `null` 이다), 경로가 비지도 `/` 도 아님, 질의·조각이 있음이면 Step 2 의 모양 문구. `require-tls` 는 스킴이 `ldaps` 가 아닌 첫 주소로 Step 2 의 TLS 문구. 클래스 자바독의 `<li><b>TLS.</b>` 줄을 목록에 맞게 고치고 주소 모양 줄을 더한다.
- `ldapContextSource`: `contextSource.setUrls(properties.getUrls().toArray(String[]::new))`.
- `LdapHealthIndicator`: 세부 `.withDetail("urls", properties.getUrls())`. `LdapHealthIndicatorTest.제때_바인드되면_UP이다` 의 `containsEntry("url", …)` 를 `containsEntry("urls", List.of("ldap://localhost:1389"))` 로.
- `application.yml`: `ldap.url: ldap://localhost:1389` → `urls:` 아래 `- ldap://localhost:1389`. `application-prod.yml` 37행 주석의 `ldap.url 이 ldaps:// 가 아니면` → `ldap.urls 에 ldaps:// 가 아닌 주소가 있으면`.

- [ ] **Step 7: 시험이 통과하는지 본다**

Run: Step 5 와 같은 명령
Expected: PASS

Run: `./gradlew :connector-ldap:test`
Expected: PASS

Run: `./gradlew :app-ldap:test --tests "dev.starryeye.organization.ldap.app.LdapHealthIndicatorTest" --tests "dev.starryeye.organization.ldap.app.LdapSyncEndToEndTest"`(뒤는 Docker 필요 — 키 이름을 바꾼 결선이 실제로 붙는지 본다)
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add connector-ldap app-ldap
```

```bash
git commit -m "feat: LDAP 주소를 목록(ldap.urls)으로 받아 JNDI 표준 목록으로 넘기고, 주소 모양과 require-tls 를 모든 주소에 검사한다 (DC 넘김)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 2: 붙은 주소·넘김 WARN·상태 점검 세부 (F5, F7 a)

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapUrls.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapTemplates.java` — `한_커넥션에서`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/LdapHealthIndicator.java` — `바인드한다`, 자바독
- Test: `connector-ldap/src/test/.../LdapFailoverTest.java`, `connector-ldap/src/test/.../LdapUrlsTest.java`(새 파일), `app-ldap/src/test/.../LdapHealthIndicatorTest.java`

**Interfaces:**
- Consumes: Task 1 의 `LdapProperties#getUrls()`, `SilentLdapServer`, `LdapFailoverTest` 의 `서버(…)`·`바인드를_센다`.
- Produces(`LdapUrls`, `public final class`, 정적 메서드):
  - `static String 주소_부분(String url)` — `스킴://권한` 만 소문자로(`URI` 의 `getScheme()` + `"://"` + `getRawAuthority()`, `Locale.ROOT`). 예: `"ldap://127.0.0.1:1234/dc=example,dc=com"` → `"ldap://127.0.0.1:1234"`, `"LDAPS://DC1:636/"` → `"ldaps://dc1:636"`.
  - `static Optional<String> 붙은_주소(DirContext ctx)` — `ctx.getEnvironment().get(Context.PROVIDER_URL)` 이 공백 없는 문자열이면 그 `주소_부분`, 없거나 공백이 들었거나(덮어쓰이지 않은 목록) `NamingException` 이면 비어 있음.
  - `static Optional<String> 첫_주소(ContextSource source)` — `AbstractContextSource` 면 `getUrls()[0]` 의 `주소_부분`, 아니면 비어 있음.

- [ ] **Step 1: `LdapUrlsTest` 를 쓴다**

| 시험 | 단언 |
|---|---|
| `주소_부분은_스킴과_권한만_소문자로_남긴다` | 위 두 예, `ldap://[::1]:389` → `ldap://[::1]:389` |
| `붙은_주소는_덮어쓰인_주소_하나일_때만_준다` | 환경에 `"ldap://dc2:389/dc=example,dc=com"` 인 가짜 `DirContext`(프록시) → `ldap://dc2:389`. `"ldap://a:389/x ldap://b:389/x"` → 비어 있음. 키 없음 → 비어 있음. `getEnvironment` 가 `NamingException` → 비어 있음 |

- [ ] **Step 2: `LdapFailoverTest` 에 붙은 주소와 WARN 시험을 더한다**

로그는 `LdapTemplates` 로거에 logback `ListAppender<ILoggingEvent>` 를 달아 본다(`DitStrategyDuplicateCodeTest` 와 같은 방식).

| 시험 | 준비 | 단언 |
|---|---|---|
| `넘긴_커넥션의_붙은_주소는_둘째_주소다` | `[닫힌_주소(), 서버 B]` 의 컨텍스트 소스에서 `getReadOnlyContext()` | `LdapUrls.붙은_주소(ctx)` 가 B 의 `주소_부분`. 이 시험이 F7 이 기대는 JDK provider 동작을 고정한다(주석으로 적는다) |
| `넘기지_않은_커넥션의_붙은_주소는_첫_주소다` | `[서버 B, 닫힌_주소()]` | 붙은 주소가 B |
| `넘긴_커넥션마다_WARN_한_줄을_남긴다` | `[닫힌_주소(), 서버 B]` 로 회차 하나를 읽는다 | WARN 이 하나 이상이고 모두 문구에 B 의 `주소_부분` 과 닫힌 주소의 `주소_부분` 이 든다. WARN 수 = 그 회차가 연 커넥션 수(`한_커넥션에서` 호출 수와 같다 — 시험에서 세지 말고 `>= 2` 로 단언) |
| `주소가_하나면_WARN_이_없다` | `[서버 B]` 로 회차 하나 | `LdapTemplates` 로거의 WARN 0 |

- [ ] **Step 3: `LdapHealthIndicatorTest` 에 시험을 더한다**

임베디드 서버는 `EmbeddedLdapListener`(connector-ldap testFixtures)로 띄운다. 인디케이터는 `new LdapConfig().ldapContextSource(properties)`(+ `afterPropertiesSet()`)로 만든 운영과 같은 컨텍스트 소스로 만든다.

| 시험 | 준비 | 단언 |
|---|---|---|
| `첫_주소가_거절하고_둘째가_바인드되면_UP_이고_붙은_주소가_둘째다` | `[닫힌_주소(), 서버 B]` | UP, 세부 `connectedUrl` 이 B 의 `주소_부분`, `urls` 가 설정 목록 |
| `첫_주소가_답하지_않으면_2초_안에_DOWN_이다` | `[SilentLdapServer.연다().접속_주소(), 서버 B]`, `connectTimeout` 기본(10초) | DOWN(`block(Duration.ofSeconds(10))` 안에 답이 온다 — 시간은 단언하지 않는다) |
| `인증_실패면_프로브_하나에_틀린_바인드는_한_번이다` | 서버 A·B 둘 다 다른 비밀번호, 바인드를 센다 | DOWN, A 와 B 가 받은 바인드의 합이 1 |

지금 시험은 그대로 통과해야 한다(가짜 컨텍스트 소스는 `connectedUrl` 이 없다 — 아래 Step 4 의 규칙).

- [ ] **Step 4: 시험이 실패하는지 본다**

Run: `./gradlew :connector-ldap:test --tests "dev.starryeye.organization.ldap.LdapUrlsTest" --tests "dev.starryeye.organization.ldap.LdapFailoverTest"`
Expected: 컴파일 실패 — `LdapUrls` 가 없다.

- [ ] **Step 5: `LdapUrls` 를 만들고 `한_커넥션에서`·`LdapHealthIndicator` 를 고친다**

- `LdapTemplates.한_커넥션에서`: `getReadOnlyContext()` 로 얻은 컨텍스트에서 `LdapUrls.붙은_주소` 와 `LdapUrls.첫_주소(template.getContextSource())` 가 둘 다 있고 다르면 `log.warn("LDAP 이 첫 주소({})가 아니라 {} 에 붙었다 — 앞 주소가 접속·바인드에 실패했다. 오래가면 ldap.urls 에서 그 주소를 뒤로 옮기거나 뺀다", 첫, 붙은)` (설계 2026-11-14 F7 a). 클래스에 `@Slf4j`.
- `LdapHealthIndicator.바인드한다`: 컨텍스트를 닫기 전에 `LdapUrls.붙은_주소` 를 읽어, 있으면 세부 `connectedUrl` 로 싣는다(없으면 싣지 않는다). 자바독에 F5(목록 하나로 바인드 하나 — 주소마다 바인드하면 틀린 비밀번호가 DC 수만큼 쌓여 잠금, 앞 주소가 2초 안에 답하지 않으면 DOWN)를 적는다.

- [ ] **Step 6: 시험이 통과하는지 본다**

Run: Step 4 의 명령
Expected: PASS

Run: `./gradlew :connector-ldap:test`
Expected: PASS

Run: `./gradlew :app-ldap:test --tests "dev.starryeye.organization.ldap.app.LdapHealthIndicatorTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add connector-ldap app-ldap
```

```bash
git commit -m "feat: 접속 단계에서 다음 LDAP 주소로 넘긴 커넥션은 WARN 을 남기고, 상태 점검 세부에 붙은 주소를 적는다 (DC 넘김)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 3: 재시도는 다음 주소부터 (F6, F7 b)

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapUrls.java` — `돌린다`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapConfig.java` — `ldapContextSource` 의 본문을 주소 목록을 받는 도우미로 나누고, `ldapDirectorySnapshotSource` 가 시작 주소별 템플릿을 만든다
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapDirectorySnapshotSource.java` — 생성자, `fetchAll`, 자바독
- Test: `connector-ldap/src/test/.../LdapFailoverTest.java`, `LdapUrlsTest.java`

**Interfaces:**
- Consumes: Task 1 의 `LdapProperties#getUrls()`, Task 2 의 `LdapUrls.주소_부분`·`LdapUrls.첫_주소`, `LdapFailoverTest` 의 도우미.
- Produces:
  - `LdapUrls.돌린다(List<String> urls, int k): List<String>` — `urls[k % n]` 부터 돈 목록(불변). `n == 0` 이면 그대로.
  - `LdapConfig.컨텍스트_소스(LdapProperties properties, List<String> urls): LdapContextSource` — `ldapContextSource` 와 같은 설정(검사·베이스·자격·JNDI 환경)에 주소만 `urls`. `ldapContextSource(properties)` 는 `컨텍스트_소스(properties, properties.getUrls())` 를 돌려준다. 이 도우미는 `afterPropertiesSet()` 을 부르지 않는다(빈은 스프링이 부른다).
  - `LdapConfig.시작_주소별_템플릿(LdapProperties properties, LdapTemplate 첫_템플릿): List<LdapTemplate>` — `[첫_템플릿, k = 1 … n-1 의 (컨텍스트_소스(properties, 돌린다(urls, k)) + afterPropertiesSet() + LdapTemplates.configured)]`. 주소가 하나면 `[첫_템플릿]`.
  - `LdapDirectorySnapshotSource(List<LdapTemplate> 시작_주소별_템플릿, LdapMappingStrategy, LdapProperties)` 와 지금 모양의 `(LdapTemplate, LdapMappingStrategy, LdapProperties)`(= `List.of(template)`). `@RequiredArgsConstructor` 를 두 생성자로 바꾼다. 빈 목록은 `IllegalArgumentException`.
  - `LdapConfig.ldapDirectorySnapshotSource(template, strategy, properties)` 의 모양은 그대로이고, 안에서 `시작_주소별_템플릿(properties, template)` 을 넘긴다.

- [ ] **Step 1: 시험을 쓴다**

`LdapUrlsTest` 에 `돌린다는_k_번째부터_돈다`: `돌린다([a,b,c], 0) = [a,b,c]`, `1 = [b,c,a]`, `4 = [b,c,a]`, `돌린다([a], 3) = [a]`.

`LdapFailoverTest` 에(Task 1 의 준비, `maxRetries` 3):

| 시험 | 준비 | 단언 |
|---|---|---|
| `첫_주소가_바인드는_받고_검색을_실패시키면_재시도가_둘째_주소부터_읽는다` | 서버 A: 검색마다 `LDAPException(ResultCode.BUSY, …)` 를 던지는 가로채기 + 검색을 센다 + 바인드를 센다. 서버 B: 정상. `[A, B]` | 그림을 읽는다. A 가 받은 검색 1, 바인드 1 |
| `다음_회차는_다시_설정_순서부터다` | 위 `source` 로 `fetchAll()` 을 한 번 더 | 다시 그림을 읽고, A 가 받은 바인드가 2 |
| `다음_주소부터_읽는_재시도는_시작_주소를_WARN_에_적는다` | 위 첫 시험과 같은 준비, `LdapDirectorySnapshotSource` 로거에 `ListAppender` | 시작 주소(B 의 `주소_부분`)가 든 WARN 이 한 줄 |
| `모든_주소가_거절하면_재시도를_다_쓰고_마지막_실패를_던진다` | `[닫힌_주소(), 닫힌_주소()]`, `maxRetries` 2 | `fetchAll().block()` 이 예외로 끝나고 그 예외가 `RetryExhaustedException` 이 아니다(지금 규칙 — 마지막 실패를 그대로) |

깨뜨릴 변경(주석): 재시도를 늘 설정 순서로 하기, 시도 수를 회차 사이에 이어 세기.

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :connector-ldap:test --tests "dev.starryeye.organization.ldap.LdapUrlsTest" --tests "dev.starryeye.organization.ldap.LdapFailoverTest"`
Expected: 컴파일 실패(`돌린다` 없음). 그것을 더한 뒤에는 첫 시험이 FAIL(재시도가 늘 A 부터라 `maxRetries` 를 다 쓰고 BUSY 로 끝난다).

- [ ] **Step 3: `LdapUrls.돌린다`, `LdapConfig.컨텍스트_소스`·`시작_주소별_템플릿`, `LdapDirectorySnapshotSource` 를 고친다**

`fetchAll` 의 모양(시도 수는 `fetchAll` 을 부를 때마다 새로 센다 — 다음 회차는 설정 순서부터):

```java
AtomicInteger 시도 = new AtomicInteger();
return Mono.defer(() -> {
            int k = 시도.getAndIncrement();
            LdapTemplate 템플릿 = 시작_주소별_템플릿.get(k % 시작_주소별_템플릿.size());
            if (k > 0 && 시작_주소별_템플릿.size() > 1) {
                log.warn("LDAP 읽기를 다음 주소부터 다시 한다 — 시작 주소 {}", LdapUrls.첫_주소(템플릿.getContextSource()).orElse("?"));
            }
            return Mono.fromCallable(() -> strategy.read(템플릿));
        })
        .subscribeOn(Schedulers.boundedElastic())
        .retryWhen(재시도_규칙(properties))
        .doOnNext(…지금 그대로…);
```

`재시도_규칙`·`다시_읽어도_같은가` 는 바꾸지 않는다. 클래스·`fetchAll` 자바독에 "재시도는 다음 주소부터 그림 전체를 읽는다 — JNDI 는 바인드 뒤의 실패를 넘기지 않는다(설계 2026-11-14 F6)" 를 더한다.

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: Step 2 의 명령
Expected: PASS

Run: `./gradlew :connector-ldap:test`
Expected: PASS(지금 `LdapDirectorySnapshotSourceTest` 는 템플릿 하나짜리 생성자를 그대로 쓴다)

Run: `./gradlew :app-ldap:test --tests "dev.starryeye.organization.ldap.app.LdapHealthIndicatorTest"`
Expected: PASS(app-ldap 컴파일 확인)

- [ ] **Step 5: 커밋**

```bash
git add connector-ldap
```

```bash
git commit -m "feat: LDAP 읽기 재시도는 다음 주소부터 그림 전체를 읽는다 — 바인드는 받고 검색에서 실패하는 DC 를 넘긴다 (DC 넘김)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 4: 주석과 문서 (F9, F10)

**Files:**
- Modify(주석만): `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java`(약 32행), `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`(약 38행), `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java`(약 65행), `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/IncompleteAttributeReadException.java`(약 11행), `UnmatchedMemberGuard.java`(약 21행), `MemberMatchingFailedException.java`(약 9행)
- Modify: `README.md`, `docs/onboarding/02-용어집.md`, `05-LDAP-동기화.md`, `11-운영.md`, `12-장애와-복구.md`, `13-한계와-알려진-문제.md`, `14-개발-가이드.md`, `15-결정-색인.md`(그 밖에 grep 으로 찾은 곳)

**Interfaces:**
- Consumes: Task 1~3 의 동작(Global Constraints)과 Task 3 커밋의 해시(마지막 코드 커밋 — 이 과제의 주석 커밋이 코드 커밋이면 그것, 아래 Step 3).

규칙: 온보딩은 늘 현재 동작이다. 문장 모양은 그 문서의 것을 따른다.

| 곳 | 고칠 것 |
|---|---|
| 주석 6곳 | `RebuildUseCase`·`AdminSyncController` 의 "임계치를 넘으면" → "임계치 또는 상한을 넘으면", `LedgerAlignment` 의 "남은 비율이 임계 아래로 내려가면" → "남은 비율이 임계치 아래로, 남은 줄 수가 상한 아래로 내려가면", connector-ldap 셋의 "삭제 가드(30%)" → "삭제 가드(30% 또는 5,000줄)". 문장의 논지는 그대로 |
| README 약 324·338행, 그 밖의 `ldap.url` | `ldap.urls`(목록). 338행의 `require-tls` 뜻은 "목록의 주소가 하나라도 `ldaps://` 가 아니면 멈춘다" |
| 02 "도메인 컨트롤러"(약 1375행) | 주소가 하나라 넘어가지 않는다 → `ldap.urls` 에 여럿을 주면 접속·바인드가 실패한 DC 를 넘기고, 재시도는 다음 주소부터, 인증 실패는 넘기지 않음 |
| 05 접속 설정(약 184·1192행) | `ldap.urls` 와 순서·넘김·재시도 규칙 한 문단(설계 §3.1), 기동 검사(F4). 재시도 문단(읽기 재시도가 그림 전체를 다시 읽는다고 적은 곳)에 "다음 주소부터" |
| 11 설정 표(약 66·77·198·331행)·상태 점검 세부(약 532·551행) | `ldap.urls` 행(기본, 순서, 쉼표·yml, 바꿀 때: DC 를 모두 적고 가까운 것을 앞에), `require-tls` 는 모든 주소, prod 가 덮지 않는 값 목록, 세부 키 `urls`·`connectedUrl`(`url` 없어짐). 상태 점검 줄에 F5(목록 하나로 바인드 하나, 앞 주소가 답하지 않으면 DOWN — 그 주소를 뒤로 옮기거나 뺀다) |
| 12 LDAP 장애(약 305행 등) | DC 하나가 내려감 → 다음 주소로 읽고 WARN("LDAP 이 첫 주소(…)가 아니라 … 에 붙었다", "LDAP 읽기를 다음 주소부터 다시 한다"), 할 일: 오래가면 목록 순서. 앞 DC 가 패킷을 버리면 회차 20~30초 늦음·상태 점검 DOWN(설계 §3.4 의 표). 모든 DC 가 내려가면 지금처럼 `FAILED` |
| 13 §1.4(약 51행)·§4.3·§4.4 | §4.3 "주소는 URL 하나(`ldap.url`)" → 목록. §4.4 제목은 그대로, 무엇·영향·우회·상태를 고친다: DC 여럿은 넘긴다(설계 2026-11-14), 남은 것은 referral·GC·DNS SRV, 넘긴 DC 의 복제 지연(설계 §6), 앞 DC 가 패킷을 버리면 회차가 늦다. 색인 표의 "다중 DC·referral" 행 |
| 14 개발 가이드(약 315행) | 앱 설정 기본값의 `ldap.url` → `ldap.urls`. 넘김 시험 도우미(`SilentLdapServer`) 한 줄 |
| 15 결정 색인 | 새 행 "LDAP 주소는 목록이고 JNDI 표준으로 넘긴다. 인증 실패는 넘기지 않는다. 재시도는 다음 주소부터. 상태 점검은 목록 하나로 바인드 하나" — 까닭(DC 하나가 내려가면 회수가 멈춘다, DC 마다 바인드하면 잠금) — 근거 [DC 넘김 설계](../superpowers/specs/2026-11-14-ldap-dc-failover-design.md) |

- [ ] **Step 1: 주석 6곳을 고치고 커밋한다**

Run: `./gradlew :core:test` — Expected: PASS(주석만이라 컴파일 확인)

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/IncompleteAttributeReadException.java connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/UnmatchedMemberGuard.java connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/MemberMatchingFailedException.java
```

```bash
git commit -m "docs: 비율만 말하던 삭제 가드 주석 6곳에 절대 수 상한을 더한다 (DC 넘김)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

- [ ] **Step 2: 위 표대로 문서를 고친다.** 고친 뒤 `grep -rn 'ldap\.url\b\|ldap\.url[^s]' README.md docs/onboarding/*.md` 로 남은 곳을 보고, 설계·계획 문서 밖에 `ldap.url` 이 남지 않게 한다.
- [ ] **Step 3: 고친 온보딩 문서 머리의 기준 커밋 줄을 `<Step 1 커밋 해시> (ldap-dc-failover, main 에 머지됨)` 으로 올린다.** Step 1 이 이 브랜치의 마지막 코드 커밋이다(`git log -1 --format=%h` 를 단일 명령으로). 건드리지 않은 문서는 그대로 둔다.
- [ ] **Step 4: 새로 단 링크의 대상 파일과 앵커를 `grep` 으로 확인한다.**
- [ ] **Step 5: 커밋**

```bash
git add README.md docs/onboarding
```

```bash
git commit -m "docs: LDAP 주소 목록과 DC 넘김을 README 와 온보딩에 반영한다 — ldap.urls·넘김·재시도·WARN·상태 점검·13 §4.4 (DC 넘김)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

## 마무리 (메인 세션)

- 전체 `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 차례로 돌린다(규모 시험도 `ldap.urls` 로 바뀐 결선을 탄다).
- 설계 §9 에 결과(검증 수, 커밋, 구현 중 판정, 최종 리뷰, 남긴 것)를 적고 커밋한다.
- 머지·푸시는 사용자의 말이 있을 때만.

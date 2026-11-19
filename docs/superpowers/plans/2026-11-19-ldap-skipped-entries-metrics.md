# 건너뛴 엔트리 지표와 입구의 길이 검사 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LDAP 읽기가 저장 한도(직원 id 500·조직 id 250·DN 1024바이트)를 넘는 엔트리를 새 사유 `한도_넘음` 으로 건너뛰게 하고, 건너뛴 수를 관찰자 → app-ldap 의 카운터 `sync.entries.skipped` 로 내며, 빈 호스트 주소(`ldap://:389`)를 기동 때 막고, README 와 온보딩을 그 동작대로 고친다.

**Architecture:** connector-ldap 에 공개 관찰자 `SkippedEntryObserver`(검색 종류·사유 열거와 `NOOP`)를 두고, 지금의 집계 `SkippedEntries` 가 경고 한 줄을 남기는 자리에서 사유마다 부른다. 길이 판정은 두 전략의 매퍼가 식별 값을 정규화한 직후 새 도우미 `EntryLimits` 로 하고, 넘으면 식별 속성 없는 엔트리처럼 나머지 속성을 읽지 않은 자리표시 레코드를 돌려준다. `LdapConfig.ldapMappingStrategy` 가 관찰자 빈을 있으면 받고, app-ldap 의 `SkippedEntryMetrics` 가 Micrometer 카운터 여덟 계열을 기동 때 0 으로 만들어 둔다.

**Tech Stack:** Java 17, Spring Boot(WebFlux)·Spring LDAP 3.3.8·JNDI, Micrometer, Lombok, JUnit 5 + AssertJ, UnboundID InMemoryDirectoryServer, logback `ListAppender`, Gradle 멀티 모듈(core, connector-ldap, app-ldap).

**Spec:** `docs/superpowers/specs/2026-11-19-ldap-skipped-entries-metrics-design.md`

## Global Constraints

- 결정은 설계 §2 의 S1~S9 이다. 설계와 이 계획이 어긋나면 설계가 이긴다.
- 한도(UTF-8 바이트, 같으면 통과·넘으면 건너뜀, S2): 직원 id `KeyValueLimits.MAX_MEMBER_ID_BYTES`(500), 조직 id 새 상수 `KeyValueLimits.MAX_GROUP_ID_BYTES = 250`, 절대 DN `KeyValueLimits.MAX_INDEXED_BYTES`(1024). id 는 정규화한 뒤의 값, DN 은 저장될 절대 DN(`externalId`)을 잰다. id 를 먼저, 그다음 DN 을 잰다.
- 새 사유는 `한도_넘음`, 요약의 설명은 `값이 저장 한도를 넘음`(S1·S4). 열거 순서: `식별_속성_없음, 한도_넘음, 아이디_겹침, 부모_조직_없음, 읽지_않은_member`.
- 예시 문구(S4, 그대로): id 가 넘으면 `dn='<절대 DN>'(id <n>바이트, 한도 <한도>)`, DN 이 넘으면 `dn='<절대 DN 앞 100 코드 포인트>…'(DN <n>바이트, 한도 1024)`. DIT 도 절대 DN 을 싣는다(잰 값이 그것이다).
- 판정은 매퍼에서 정규화 직후, 나머지 속성(계정 상태, `primaryGroupID`·`objectSid`, `member`)을 읽기 전이다. 순서는 식별 속성 없음 → 한도 넘음 → 겹침(S3).
- 관찰자는 `SkippedEntries.요약을_남긴다()` 가 경고를 남긴 뒤, 건수가 있는 사유마다 한 번 부른다. 관찰자가 던진 `RuntimeException` 은 호출마다 잡아 WARN `건너뛴 엔트리 수를 관찰자에게 알리지 못했다 — {}`(인자는 `e.toString()`)을 남기고 삼킨다(S5).
- 지표(S6): 카운터 `sync.entries.skipped`, 태그 `search` = 직원 `user`·조직 `group`·`member값` `member`, `reason` = `식별_속성_없음` `missing_id`·`아이디_겹침` `duplicate_id`·`한도_넘음` `over_limit`·`부모_조직_없음` `parent_not_found`·`읽지_않은_member` `unmatched_member`. 기동 때 0 으로 만드는 여덟 계열: `user`×{`missing_id`,`duplicate_id`,`over_limit`,`parent_not_found`}, `group`×{`missing_id`,`duplicate_id`,`over_limit`}, `member`×{`unmatched_member`}.
- 빈 호스트(S8): 권한부가 `:` 로 시작하면 모양이 틀린 주소다. 문구는 지금 모양 오류 그대로다.
- 바꾸지 않는 것: 표시명·계정명의 길이, SCIM, 지금 사유 넷의 뜻과 문구, 멈추는 규칙, core 유스케이스·`DirectorySnapshot`·`SyncRun`, storage-dynamodb.
- 서브에이전트는 모듈 시험만 돌린다: `./gradlew :core:test --tests <클래스>`, `./gradlew :connector-ldap:test --tests <클래스>`, `./gradlew :app-ldap:test --tests <클래스>`. Gradle 은 한 번에 하나만 돈다. 전체 `cleanTest test`·`cleanScaleTest scaleTest` 는 메인 세션이 머지 전에 돌린다.
- git 은 꾸밈 없는 단일 명령으로 쓴다(`&&`·`$(...)` 금지 — 작업 트리 가드가 거절한다). `git stash` 금지.
- 커밋 메시지는 `<종류>: <한국어 요약> (건너뛴 엔트리 지표)` 이고, 끝 줄은 `Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>` 이다.
- 식별자·자바독·주석은 이 저장소처럼 한국어로 쓴다. 자바독에 근거 설계를 `설계 2026-11-19 S<n>` 꼴로 적는다.
- 시험은 벽시계 시각을 단언하지 않는다. 로그에 `externalId` 를 새로 싣는 곳은 LDAP 의 예시 DN 뿐이다(지금 사유들과 같다).

## Review Focus

1. **한도를 넘은 그룹의 `member` 가 깨졌거나 범위로 잘렸다** — 그 그룹의 멤버는 읽지 않는다(이어받기 질의도, DN 해석 실패도 없다). → Task 2 의 `한도를_넘은_그룹의_member_는_읽지_않는다`.
2. **4바이트 문자(이모지 등)로 된 긴 DN** — 예시는 코드 포인트 경계에서 100개로 자르고 홀로 남은 서로게이트가 없다. → Task 2 의 `긴_DN_의_예시는_코드_포인트로_자른다`.
3. **관찰자 빈이 없는 문맥** — 전략 빈은 `NOOP` 로 만들어지고 기동이 실패하지 않는다. → Task 3 의 `관찰자_빈이_없으면_NOOP_로_만든다`.
4. **IPv6·밑줄 든 호스트** — 빈 호스트 규칙 뒤에도 `ldap://[::1]:389`, `ldap://dc_1.corp:389` 는 지난다. → Task 3 의 `빈_호스트는_멈추고_다른_모양은_그대로다`.
5. **한도를 넘은 직원의 다른 값이 표준 밖** — `userAccountControl: abc`, `primaryGroupID: abc` 여도 회차가 성공한다. → Task 2 의 `한도를_넘은_직원은_다른_속성을_읽지_않는다`.

---

### Task 1: 관찰자와 집계 (S1·S4·S5 의 집계 쪽)

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/SkippedEntryObserver.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/SkippedEntries.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/KeyValueLimits.java`
- Modify (컴파일을 맞추는 만큼만): `GroupOfNamesStrategy.java`, `DitStrategy.java` — `SkippedEntries.사유` → `SkippedEntryObserver.사유`, 새 생성자에 `SkippedEntryObserver.NOOP`(관찰자 결선은 Task 2)
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/SkippedEntriesTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface SkippedEntryObserver {
      enum 검색 { 직원, 조직, member값 }
      enum 사유 { 식별_속성_없음, 한도_넘음, 아이디_겹침, 부모_조직_없음, 읽지_않은_member }
      void skipped(검색 search, 사유 reason, int count);
      SkippedEntryObserver NOOP = (search, reason, count) -> { };
  }
  ```
  - `SkippedEntries(SkippedEntryObserver.검색 검색, String 식별속성, SkippedEntryObserver 관찰자)` — 지금 `(String, String)` 을 대신한다. 메시지의 검색 이름은 직원 `직원`, 조직 `조직`(지금 문구 그대로).
  - `static SkippedEntries member값(SkippedEntryObserver 관찰자)` — 검색 종류 `member값`, 머리말 `member 값 `(지금 그대로).
  - `void 한도_넘음을_기록한다(String 예시문구)` 는 두지 않는다 — `기록한다(사유.한도_넘음, 예시)` 를 쓴다.
  - `KeyValueLimits.MAX_GROUP_ID_BYTES = 250` (public static final int).

- [ ] **Step 1: 실패하는 시험을 쓴다.** `SkippedEntriesTest` 의 지금 시험은 새 생성자(`SkippedEntryObserver.검색.직원`, `NOOP`)와 `SkippedEntryObserver.사유` 로 옮기고, 아래를 더한다. 관찰자는 호출을 `List<String>`(`"직원/한도_넘음/2"` 꼴)에 쌓는 시험용 람다로 만든다.

```java
@Test
void 요약을_남기면_사유마다_건수로_관찰자를_부른다() {
    // 식별_속성_없음 2건, 아이디_겹침 1건 → 요약을_남긴다()
    assertThat(호출).containsExactly("직원/식별_속성_없음/2", "직원/아이디_겹침/1");
}

@Test
void 건너뛴_것이_없으면_관찰자를_부르지_않는다() {
    // 기록 없이 요약을_남긴다()
    assertThat(호출).isEmpty();
}

@Test
void member_값_집계는_member_검색으로_부른다() {
    // SkippedEntries.member값(관찰자) 에 읽지_않은_member 3건 → 요약을_남긴다()
    assertThat(호출).containsExactly("member값/읽지_않은_member/3");
}

@Test
void 관찰자가_던져도_경고는_남고_예외는_나가지_않는다() {
    // 관찰자 = (s, r, c) -> { throw new IllegalStateException("지표 고장"); }, 두 사유 기록
    assertThatCode(건너뜀::요약을_남긴다).doesNotThrowAnyException();
    // 로그(ListAppender, 로거 dev.starryeye.organization.ldap.strategy): "직원 검색에서 엔트리 " 로 시작하는 WARN 1줄,
    // "건너뛴 엔트리 수를 관찰자에게 알리지 못했다 — java.lang.IllegalStateException: 지표 고장" WARN 2줄(사유마다)
}

@Test
void 한도_넘음의_설명과_예시() {
    // 기록한다(한도_넘음, "dn='uid=x,ou=people,dc=example,dc=com'(id 612바이트, 한도 500)")
    assertThat(건너뜀.요약()).contains("값이 저장 한도를 넘음 1건")
            .contains("dn='uid=x,ou=people,dc=example,dc=com'(id 612바이트, 한도 500)");
}
```

- [ ] **Step 2: 시험이 실패하는지 본다.** Run: `./gradlew :connector-ldap:test --tests 'dev.starryeye.organization.ldap.strategy.SkippedEntriesTest'` — Expected: 컴파일 실패(`SkippedEntryObserver` 없음).
- [ ] **Step 3: `SkippedEntryObserver` 를 만들고 `SkippedEntries` 를 고친다.** `SkippedEntries.사유` 를 지우고 `SkippedEntryObserver.사유` 를 쓴다. `요약을_남긴다()` 는 지금 경고를 남긴 뒤 `건수` 의 사유 순서(EnumMap)대로 관찰자를 부르고, 호출마다 Global Constraints 의 WARN 으로 예외를 삼킨다. `설명(사유)` 에 `한도_넘음 -> "값이 저장 한도를 넘음"`. 클래스 자바독의 "사유는 셋" 을 넷으로, 관찰자를 적는다(설계 2026-11-19 S5).
- [ ] **Step 4: `KeyValueLimits.MAX_GROUP_ID_BYTES = 250` 을 더한다.** 자바독 근거: OpenFGA `object` 칸은 `^[^\s]{2,256}$`(openfga/api `TupleKey`, 설계 2026-11-19 §1)라 `group:<id>` 의 id 는 250자까지, `user` 칸(512바이트)으로는 506바이트까지 — 바이트 250 이면 둘 다 지킨다(1자는 1바이트 이상). LDAP 입구가 쓴다(SCIM 의 조직 id 는 서버가 정하는 UUID 다). 클래스 자바독 목록에 한 줄.
- [ ] **Step 5: 두 전략을 컴파일만 맞춘다.** `new SkippedEntries("직원", …)` → `new SkippedEntries(SkippedEntryObserver.검색.직원, …, SkippedEntryObserver.NOOP)`, `조직` 도 같다, `SkippedEntries.member값()` → `member값(SkippedEntryObserver.NOOP)`, `SkippedEntries.사유.X` → `SkippedEntryObserver.사유.X`. 동작은 바꾸지 않는다.
- [ ] **Step 6: 시험이 통과하는지 본다.** Run: `./gradlew :connector-ldap:test` — Expected: PASS(모듈 전체 — 사유 이름을 옮긴 것이 다른 시험을 깨지 않는지).
- [ ] **Step 7: 커밋**

```bash
git add connector-ldap/src core/src/main/java/dev/starryeye/organization/core/model/KeyValueLimits.java
```

```bash
git commit -m "feat: 건너뛴 엔트리 수를 알리는 관찰자와 새 사유 한도_넘음, 조직 id 한도 250바이트 상수를 둔다 — 경고 한 줄을 남길 때 사유마다 건수로 부르고 관찰자 오류는 삼킨다 (건너뛴 엔트리 지표)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 2: 두 전략의 길이 검사와 관찰자 생성자 (S1~S4·S7 의 전략 쪽)

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/EntryLimits.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/DitStrategy.java`
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/EntryLimitsTest.java`
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesLengthLimitTest.java`
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/DitLengthLimitTest.java`

**Interfaces:**
- Consumes: Task 1 의 `SkippedEntryObserver`(열거 둘·`NOOP`), `SkippedEntries` 새 생성자, `KeyValueLimits.MAX_GROUP_ID_BYTES`.
- Produces:
  - `final class EntryLimits` (패키지 안): `static final int 예시_DN_코드_포인트 = 100;` 과 `static Optional<String> 넘는_값(String 정규화한_id, String 절대DN, int id한도)` — 넘지 않으면 비었고, 넘으면 Global Constraints 의 예시 문구다. DN 한도는 `KeyValueLimits.MAX_INDEXED_BYTES`.
  - `public GroupOfNamesStrategy(LdapProperties properties, Clock clock, SkippedEntryObserver 관찰자)`, `public DitStrategy(LdapProperties properties, Clock clock, SkippedEntryObserver 관찰자)`. 지금 생성자 `(properties, clock)`·`(properties)` 는 남기고 `NOOP` 로 위임한다.

- [ ] **Step 1: `EntryLimitsTest` 를 쓴다(실패).**

```java
@Test void 한도와_같으면_넘지_않는다()          // id "a"×500, 한도 500, 짧은 DN → Optional.empty()
@Test void 한도를_하나_넘으면_id_문구다()        // id "a"×501 → "dn='uid=x,dc=example,dc=com'(id 501바이트, 한도 500)"
@Test void 바이트로_잰다()                     // id "가"×84(252바이트), 한도 250 → "(id 252바이트, 한도 250)"
@Test void DN_이_넘으면_앞_100_코드_포인트와_말줄임() // id 짧음, DN = "cn=" + "b"×1100 + ",dc=example,dc=com"
                                                // → "dn='" + DN 앞 100자 + "…'(DN " + DN 바이트 + "바이트, 한도 1024)"
@Test void 둘_다_넘으면_id_를_적는다()           // id·DN 모두 넘음 → "(id …" 로 끝나는 문구
@Test void 긴_DN_의_예시는_코드_포인트로_자른다()  // DN = "cn=" + "😀"×300 + ",dc=example,dc=com"
                                                // → 예시의 DN 부분이 DN.codePoints() 앞 100개와 같고, 홀로 남은 서로게이트가 없다
                                                //   (문구.codePoints() 에 Character.isSurrogate 인 값이 없다)
```

- [ ] **Step 2: 실패를 본다.** Run: `./gradlew :connector-ldap:test --tests 'dev.starryeye.organization.ldap.strategy.EntryLimitsTest'` — Expected: 컴파일 실패.
- [ ] **Step 3: `EntryLimits` 를 만든다.** 바이트는 `getBytes(StandardCharsets.UTF_8).length`. DN 은 코드 포인트로 자른다(`offsetByCodePoints`).
- [ ] **Step 4: 통과를 본다.** 같은 명령 — Expected: PASS.
- [ ] **Step 5: 전략 시험을 쓴다(실패).** `EmbeddedLdapSupport` 를 상속하고(LDIF 는 시험마다), 경고는 `ListAppender`(로거 `dev.starryeye.organization.ldap.strategy`)로 본다 — 기존 `GroupOfNamesDuplicateIdTest`·`DitStrategyDuplicateCodeTest` 의 모양. 식별 속성은 RDN 이 아닌 문자열 속성으로 둔다(예: 직원 `employeeNumber`, 조직 `businessCategory` — 스키마를 끈 임베디드 서버라 아무 속성이나 받는다). 사람 소속이 하나도 없는 그림은 데이터 오류라 LDIF 마다 한도 안의 직원 하나를 한도 안의 조직에 둔다. 관찰자가 넘어가는지 보려고 3인자 생성자에 기록용 관찰자를 넣는다.

두 클래스 모두(`GroupOfNamesLengthLimitTest`, `DitLengthLimitTest`):

```java
@Test void 직원_id_가_500바이트면_남고_501바이트면_빠진다()
    // 그림의 직원 id 집합에 500바이트 쪽만. 경고에 "값이 저장 한도를 넘음 1건" 과 "(id 501바이트, 한도 500)"
@Test void 조직_id_가_250바이트면_남고_251바이트면_빠진다()
    // 그림의 조직 id 집합에 250바이트 쪽만. 경고에 "(id 251바이트, 한도 250)"
@Test void 한글_조직_id_는_바이트로_재어_빠진다()
    // 조직 id "가"×84(252바이트) → 빠짐
@Test void 절대_DN_이_1024바이트를_넘으면_빠진다()
    // id 는 짧고 DN 이 1024바이트를 넘는 직원 → 빠짐, 경고에 "(DN " 과 "…'". DIT 는 베이스를 붙인 절대 DN 으로 잰다
    // (임베디드 서버가 그런 DN 을 받지 않으면 이 시험을 빼고 EntryLimitsTest 로 갈음한다 — 설계 §11. 보고서에 적는다)
@Test void 한도를_넘은_직원은_다른_속성을_읽지_않는다()
    // 한도를 넘은 직원에 userAccountControl: abc (groupOfNames 는 primaryGroupID: abc 도) → read() 가 던지지 않고 그 직원만 빠진다
@Test void 모두_넘으면_멈춘다()
    // 직원 전원의 id 가 넘음 → DirectoryDataException, 메시지에 "하나도 남지 않았다" 와 "값이 저장 한도를 넘음"
@Test void 전략이_관찰자에_건너뛴_수를_알린다()
    // 3인자 생성자 + 기록용 관찰자, 직원 하나가 넘음 → 호출에 (직원, 한도_넘음, 1)
```

groupOfNames 만:

```java
@Test void 빠진_직원을_가리키는_member_는_대조되지_않은_값으로_센다()
    // 한도를 넘은 직원을 member 로 둔 그룹 → 경고 "member 값 1건을 건너뛰었다", 그 그룹의 멤버에 그 직원이 없다
@Test void 한도를_넘은_그룹의_member_는_읽지_않는다()
    // 한도를 넘은 그룹에 "member: 이건 DN 이 아니다" → read() 가 던지지 않는다(멤버를 읽으면 대조키 해석이 DirectoryDataException 을 던진다)
```

DIT 만:

```java
@Test void 한도를_넘어_빠진_OU_아래_직원은_소속_없이_남는다()
    // 한도를 넘은 OU 아래 직원 → 그림에 직원은 있고 어느 조직의 멤버도 아니다, 경고에 "부모 조직을 찾지 못해 소속 없이 적재 1건"
```

- [ ] **Step 6: 실패를 본다.** Run: `./gradlew :connector-ldap:test --tests 'dev.starryeye.organization.ldap.GroupOfNamesLengthLimitTest' --tests 'dev.starryeye.organization.ldap.DitLengthLimitTest'` — Expected: 3인자 생성자가 없어 컴파일 실패.
- [ ] **Step 7: 두 전략을 고친다.**
  - 생성자: `SkippedEntryObserver 관찰자` 필드를 더하고(롬복 `@RequiredArgsConstructor` 가 3인자를 만들게 두든 직접 쓰든), 2인자·1인자는 `NOOP` 로 위임한다. Task 1 이 넣은 `NOOP` 대신 이 필드를 `SkippedEntries` 에 넘긴다.
  - 매퍼: 식별 값을 정규화한 직후 `EntryLimits.넘는_값(id, 절대DN, 한도)` — 직원은 `KeyValueLimits.MAX_MEMBER_ID_BYTES`, 조직(groupOfNames 그룹·DIT OU)은 `KeyValueLimits.MAX_GROUP_ID_BYTES`. DIT 의 절대 DN 은 `LdapDns.절대로(dn, properties.getBaseDn())`. 넘으면 나머지 속성을 읽지 않은 자리표시 레코드를 돌려준다 — 레코드에 넘은 문구를 실을 칸(예: `String 넘음`, 보통은 null)과 정적 생성자(예: `한도_넘음(dn, 문구)`)를 더한다. groupOfNames 의 그룹 자리표시는 `식별_속성_없음` 처럼 멤버를 비우고 완료(`membersComplete = true`)로 둔다 — 이어받기 대상이 되지 않는다.
  - 루프: 식별 속성 없음 검사와 같은 자리에서 넘은 레코드면 `기록한다(사유.한도_넘음, 문구)` 하고 건너뛴다. 겹침 색인(`DuplicateIdGuard`)에 넣지 않는다. 남은 수 계산(`아무도_남지_않으면_멈춘다`)은 지금 그대로다.
  - 자바독: 레코드의 새 칸, 매퍼의 판정 자리(설계 2026-11-19 S2·S3).
- [ ] **Step 8: 통과를 본다.** Run: `./gradlew :connector-ldap:test` — Expected: PASS(모듈 전체).
- [ ] **Step 9: 커밋**

```bash
git add connector-ldap/src
```

```bash
git commit -m "feat: 정규화한 id(직원 500·조직 250바이트)나 절대 DN(1024바이트)이 저장 한도를 넘는 엔트리는 매퍼에서 나머지 속성을 읽지 않고 한도_넘음 으로 건너뛰고, 두 전략이 관찰자를 받는다 (건너뛴 엔트리 지표)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 3: 결선·지표·빈 호스트 (S6~S8)

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapConfig.java`
- Create: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SkippedEntryMetrics.java`
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapConfigValidationTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SkippedEntryMetricsTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SkippedEntryMetricsWiringTest.java`

**Interfaces:**
- Consumes: Task 1 의 `SkippedEntryObserver`, Task 2 의 3인자 전략 생성자.
- Produces:
  - `public LdapMappingStrategy ldapMappingStrategy(LdapProperties properties, Clock clock, ObjectProvider<SkippedEntryObserver> 관찰자)` — `관찰자.getIfAvailable(() -> SkippedEntryObserver.NOOP)` 를 3인자 생성자에 넘긴다.
  - `@Component public class SkippedEntryMetrics implements SkippedEntryObserver` — 생성자 `SkippedEntryMetrics(MeterRegistry registry)`.

- [ ] **Step 1: 실패하는 시험을 쓴다.**

`LdapConfigValidationTest` 에:

```java
@Test
void 빈_호스트는_멈추고_다른_모양은_그대로다() {
    // ldap.urls = ["ldap://:389"] → IllegalStateException, 메시지에 "ldap://호스트[:포트]" 와 "받은 값: ldap://:389"
    // ldap.urls = ["ldap://[::1]:389", "ldap://dc_1.corp:389", "ldaps://dc1"] → 예외 없음
}
```

`SkippedEntryMetricsTest`(`SimpleMeterRegistry`):

```java
@Test void 기동_때_여덟_계열이_0_으로_있다()
    // registry.find("sync.entries.skipped").counters() 의 (search, reason) 집합이 Global Constraints 의 여덟과 같고 모두 0
@Test void 받은_건수만큼_그_계열만_오른다()
    // skipped(직원, 한도_넘음, 3) → {user, over_limit} 3.0, 나머지 일곱은 0
@Test void 태그는_고정한_영문이다()
    // 여덟 조합을 서로 다른 건수(1~8)로 부르고 각 (search, reason) 태그의 값이 그 건수다
    // — 열거 값마다 태그 문자열을 못박는다(예: 읽지_않은_member → unmatched_member, member값 → member)
```

`SkippedEntryMetricsWiringTest`(`ApplicationContextRunner` — `SyncSchedulerCronTest` 의 모양. 사용자 설정 `LdapConfig`, 빈 `SimpleMeterRegistry`·`Clock.systemUTC()`, 속성 `ldap.urls`(임베디드 서버, `EmbeddedLdapListener.접속_주소`)·`ldap.base-dn`·`ldap.bind-dn`·`ldap.bind-password`·`ldap.group-of-names.user-id-attribute=employeeNumber`):

```java
@Test void 문맥의_전략이_지표에_알린다()
    // + SkippedEntryMetrics 빈. LDIF: employeeNumber 있는 직원 하나(조직 멤버), 없는 직원 하나
    // context.getBean(LdapMappingStrategy.class).read(context.getBean(LdapTemplate.class)) 뒤
    // sync.entries.skipped{search=user, reason=missing_id} 가 1.0
@Test void 관찰자_빈이_없으면_NOOP_로_만든다()
    // SkippedEntryMetrics 없이 → 문맥이 뜨고(hasNotFailed) 같은 read() 가 던지지 않는다
```

- [ ] **Step 2: 실패를 본다.** Run: `./gradlew :connector-ldap:test --tests 'dev.starryeye.organization.ldap.LdapConfigValidationTest'` — Expected: 빈 호스트 시험 FAIL(예외 없음). 이어서 `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.SkippedEntryMetrics*'` — Expected: 컴파일 실패.
- [ ] **Step 3: `LdapConfig` 를 고친다.** `주소_모양이_맞다` 에서 권한부가 `:` 로 시작하면 false(사용자 정보는 이미 막으므로 권한부는 `호스트[:포트]` 다 — 설계 2026-11-19 S8). 클래스 자바독의 주소 목록 항목에 "호스트가 비었거나(`ldap://:389`)". `ldapMappingStrategy` 는 Interfaces 의 서명으로.
- [ ] **Step 4: `SkippedEntryMetrics` 를 만든다.** 생성자에서 여덟 계열을 `Counter.builder("sync.entries.skipped").description(…).tag("search", …).tag("reason", …).register(registry)` 로 0 으로 만든다(`ScimSyncMetrics` 의 `scim.writes.unsourced` 와 같은 까닭 — 자바독에 적는다). `skipped` 는 태그로 카운터를 찾아 `increment(count)`. description: `LDAP 읽기에서 건너뛴 엔트리(member 는 값) 수 — 경고 "… 건너뛰었다" 와 같은 수. 읽기마다 센다`.
- [ ] **Step 5: 통과를 본다.** Run: `./gradlew :connector-ldap:test --tests 'dev.starryeye.organization.ldap.LdapConfigValidationTest'` 그다음 `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.SkippedEntryMetrics*'` — Expected: PASS.
- [ ] **Step 6: 커밋**

```bash
git add connector-ldap/src app-ldap/src
```

```bash
git commit -m "feat: app-ldap 이 건너뛴 엔트리 수를 sync.entries.skipped 카운터(search·reason, 여덟 계열을 기동 때 0)로 내고, 빈 호스트 주소(ldap://:389)는 기동을 멈춘다 (건너뛴 엔트리 지표)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 4: README 와 온보딩을 코드대로 고친다 (S9)

**Files:**
- Modify: `README.md`, `docs/onboarding/02-용어집.md`, `03-아키텍처.md`, `04-데이터-모델.md`, `05-LDAP-동기화.md`, `11-운영.md`, `12-장애와-복구.md`, `13-한계와-알려진-문제.md`, `15-결정-색인.md`

**Interfaces:**
- Consumes: Task 1~3 의 동작(Global Constraints 의 값·문구·지표 이름·태그)과 Task 3 커밋의 해시(`git log -1 --format=%h` 를 단일 명령으로 — Task 4 를 시작할 때 HEAD 가 Task 3 의 마지막 코드 커밋이다).

규칙: 온보딩은 늘 현재 동작이다. 문장 모양은 그 문서의 것을 따른다. 행 번호는 어림이다 — `grep` 으로 찾는다.

| 곳 | 고칠 것 |
|---|---|
| README 약 1001~1004행(건너뛰는 엔트리 목록) | 한 줄을 더한다: 정규화한 id 가 직원 500·조직 250바이트, 절대 DN 이 1024바이트를 넘는 엔트리 — 건너뛴다(쓰면 OpenFGA·DynamoDB 가 거절한다). 목록 뒤에 지표 한 문단: `sync.entries.skipped`(Prometheus `sync_entries_skipped_total{search,reason}`)가 경고와 같은 수로 오르고 기동 때 0 으로 있다, 설계 §3.3 의 첫 경보 예와 `unmatched_member` 를 뺀 까닭 |
| 02 약 320행(식별 속성) | 한도를 넘는 값의 엔트리도 건너뛴다는 것과 한도 셋 |
| 03 약 423행(열거값 예) | `SkippedEntries.사유` → `SkippedEntryObserver.사유` |
| 04 §1 "튜플 칸의 길이"(약 151~153행) | `object` 칸 256자(openfga/api 검증 규칙에서 읽음, 실제 OpenFGA 로 재현하지 않음)와 그래서 조직 id 가 250바이트라는 것. "다른 문서는 이 한도를 말할 때 이 절을 가리킨다" 는 그대로 |
| 04 §6 "막지 않는 것"(약 852~854행) | 제목을 "LDAP 입구에서 막는 것" 꼴로 바꾸고 내용을 현재로: 매퍼가 한도를 넘는 엔트리를 건너뛴다(05 §3.11 링크). 표시명·계정명은 여전히 색인 키만 자른다. 이 절을 가리키는 링크(`#막지-않는-것`)를 grep 으로 찾아 새 앵커로 고친다 |
| 05 흐름의 단계(약 123·124·137·175·201·282행) | "식별 속성이 없거나 id 가 겹치는" 에 "한도를 넘는" 을 더한다. 175행(id 읽기)에 한도 셋과 재는 값(정규화한 id, 절대 DN). 282행에 한도를 넘는 엔트리도 계정 상태를 읽지 않는다는 것 |
| 05 §3.11(약 285~305행) | 사유 표에 `한도_넘음` 행(무엇: 정규화한 id 가 직원 500·조직 250바이트, 절대 DN 이 1024바이트를 넘음 / 결과: 그 엔트리를 뺀다, 나머지 속성은 읽지 않는다, 예시 문구 두 꼴). 표 머리의 `SkippedEntries.사유` → `SkippedEntryObserver.사유`. 경고 모양에 설계 §3.3 의 예. 표 아래 "지표" 문단: 경고마다 사유별 건수가 `sync.entries.skipped` 에 오른다(11 §3.2 링크), 읽기마다 센다. 마지막 문단 "첫 적재에서는 경고 한 줄이 유일한 신호다" → 경고와 지표 |
| 05 약 1212행(설계 목록) | 이 설계 한 줄 |
| 11 §3.1(약 350·351행) | "기동 때 0 으로 만들어 둔다" 목록에 `sync.entries.skipped` 의 여덟 계열(`SkippedEntryMetrics` 생성자). "처음 올라갈 때 만들어진다" 의 `sync.*` 에서 이것을 뺀다 |
| 11 §3.2(약 358~364행 표) | 행: `sync.entries.skipped` / Counter / `search`·`reason`(값 나열) / 뜻(건너뛴 엔트리 수, `member` 는 값, 경고와 같은 수, 읽기마다 — 재시도·재적재도 센다). 표 위 "`SyncMetrics.record` 가 … 부른다" 는 회차 지표의 말이라, 이 행은 `SkippedEntryMetrics` 가 읽기 중에 올린다고 밝힌다. 약 676행 "그 회차를 돌린 인스턴스에만 오르므로" 목록에 더한다 |
| 11 "덧붙인 예"(약 505~520행) | 설계 §3.3 의 경보 예 둘과 아래 주석 둘(`unmatched_member` 를 뺀 까닭, 영구히 빠지는 엔트리는 필터로 뺀다) |
| 12 §2.4 "원인 예"(약 135행) | LDAP 은 이제 한도를 넘는 식별 값을 입구에서 건너뛴다(05 §3.11) — 이 장애의 LDAP 원인에서 길이는 빠진다 |
| 12 §4.7 "긴 값"(약 377~382행) | 긴 DN·식별 값 항목을 현재로: 그 엔트리만 빠지고 회차는 평소대로, 보이는 것은 경고 "값이 저장 한도를 넘음" 과 `sync.entries.skipped{reason="over_limit"}`, 할 일은 식별 속성을 짧은 불변 속성으로 바꾸거나 DN 을 줄이거나 검색 범위 밖에 둔다. "미리 막는 장치가 없다" 를 지운다 |
| 13 머리말(약 21행) | "앞 판이 적었던 번호는 1절, 6절, 7절 머리의 표에" 에 4절을 더한다 |
| 13 §3.6·§3.7 | §3.6 영향: LDAP 은 한도를 넘는 id 를 입구에서 빼므로 길이로는 생기지 않는다. §3.7: "건너뛴 엔트리의 경고 한 줄이 유일한 신호다" → 경고와 `sync.entries.skipped` |
| 13 §4 머리 | §1·§6 처럼 "이 절의 앞 판에 있던 항목 가운데 고친 것이다" 표를 둔다: 4.1 "긴 DN·식별 값은 미리 막지 않는다" → 고침(<Task 2 커밋>) — 한도를 넘는 엔트리를 건너뛴다([05 §3.11](05-LDAP-동기화.md#311-건너뛰는-엔트리)). 4.7 "건너뛴 엔트리는 로그로만 보인다" → 고침(<Task 3 커밋>) — `sync.entries.skipped`([11 §3.2](11-운영.md#32-app-ldap)). 두 절을 지운다. 번호는 다시 매기지 않는다(4.2~4.6 그대로) |
| 13 §8 한눈에 | 4.1·4.7 행을 지운다 |
| 13·다른 문서의 링크 | `13-한계와-알려진-문제.md#41-` 와 `#47-` 로 가는 링크를 grep 으로 찾아 §4 머리의 표(앵커 `#4-ldap-배포`)로 고친다 |
| 15 설계 목록(약 102~106행) | 2026-11-14 행의 "현재(가장 최신)" → "현재". 새 행: `[2026-11-19 건너뛴 엔트리 지표](../superpowers/specs/2026-11-19-ldap-skipped-entries-metrics-design.md)` / 13 §4.1·§4.7 — 한도(직원 id 500·조직 id 250·DN 1024바이트)를 넘는 엔트리를 `한도_넘음` 으로 건너뜀, `sync.entries.skipped`(기동 때 0), 빈 호스트 기동 검사 / 현재(가장 최신). [LDAP 읽기](#ldap-읽기). 계획 수 46 → 47 |
| 15 LDAP 읽기 결정 표(약 310행 근처) | 행 둘: ① 한도를 넘는 엔트리는 건너뛴다(값·재는 법) — 까닭: 쓰면 거절될 줄 하나가 매 회차 뒤 단계 부여를 막는다 — 버린 길: 회차를 멈춤, 해시 — 근거 설계 §2 S1·S2. ② 건너뛴 수를 카운터로(기동 때 0, 영문 태그) — 까닭: 경보, 인스턴스를 더해도 맞다 — 버린 길: 게이지, 실행 기록에 싣기 — 근거 §2 S5·S6, §7 |

- [ ] **Step 1: 위 표대로 고친다.** 고친 뒤 `grep -rn "SkippedEntries.사유\|막지 않는다\|미리 막는 장치\|로그로만" README.md docs/onboarding` 로 남은 곳을 하나씩 본다.
- [ ] **Step 2: 고친 온보딩 문서 머리의 기준 커밋 줄을 `<Task 3 커밋 해시> (ldap-skip-metrics, main 에 머지됨)` 으로 올린다.** 건드리지 않은 문서는 그대로 둔다.
- [ ] **Step 3: 링크를 확인한다.** 새로 단 링크와 고친 앵커의 대상 파일·제목이 있는지 `grep` 으로 본다.
- [ ] **Step 4: 커밋**

```bash
git add README.md docs/onboarding
```

```bash
git commit -m "docs: 한도를 넘는 엔트리의 건너뛰기와 sync.entries.skipped 를 README 와 온보딩에 반영하고, 13 §4.1·§4.7 을 고친 항목으로 옮긴다 (건너뛴 엔트리 지표)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

## 마무리 (메인 세션)

- 전체 `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 차례로 돌린다. 기존 규모 시험은 식별 속성이 `entryUUID`(36자)라 한도에 닿지 않아 그대로 통과해야 한다.
- 설계 §9 에 결과(검증 수, 커밋, 구현 중 판정, 최종 리뷰, 남긴 것)를 적고 커밋한다.
- 머지·푸시는 사용자의 말이 있을 때만.

# 정리 묶음 (⑤) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 리뷰 문서 §5 와 슬라이드 E·①·③ 의 이월 항목 중 **아직 남아 있는 것**을 치운다.

**Architecture:** 운영 코드는 `connector-ldap` 두 곳만 동작이 바뀐다(`DitStrategy` 의 DN 처리, 범위 이어받기의 다음 위치). 나머지는 테스트 코드다. 규모 테스트 12개 클래스의 중복은 `authz-openfga` testFixtures 의 두 클래스로 모으고, 컨테이너 인스턴스는 클래스마다 따로 둔다.

**Tech Stack:** Java 17, Spring Boot 3.5 (`@MockitoSpyBean`), JUnit 5, AssertJ, Mockito, Testcontainers, UnboundID, `javax.naming.ldap.LdapName`.

**Spec:** [`docs/superpowers/specs/2026-09-25-cleanup-bundle-design.md`](../specs/2026-09-25-cleanup-bundle-design.md)

## Global Constraints

- 테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석. 기존 테스트의 형태를 그대로 따른다.
- Lombok 을 쓴다. 로깅은 `@Slf4j`.
- **운영 동작이 바뀌는 곳은 Task 1·2 뿐이다.** 다른 과제에서 운영 코드(`src/main`)를 고치면 자바독·주석·죽은 코드 삭제만 허용한다.
- ⑥(LDAP 비활성 계정)은 이 계획의 범위가 아니다.
- **Gradle 규칙:** 서브에이전트는 자기 과제의 모듈 테스트(`:모듈:test`)와, 과제가 명시한 **규모 테스트 클래스 하나씩**만 포그라운드로 돌린다. 루트 `test`·`build`·`check`·`scaleTest` 전체는 돌리지 않는다. Gradle 을 둘 이상 동시에 돌리지 않는다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. **RDN 값 안의 이스케이프된 쉼표** — `ou=R\,D` 같은 OU 도, `cn=Lee\, Minho` 같은 직원도 부모 조직을 찾아야 한다. Task 1 이 둘 다 테스트한다.
2. **범위 응답의 값 개수가 범위 표기와 다를 때** — 다음 요청은 표준대로 `상한 + 1` 에서 시작해야 한다. Task 2 가 테스트한다.
3. **순환이 든 조직도에 `조상들` 을 부르면** — 무한 루프가 아니라 즉시 예외. Task 4 가 `@Timeout` 으로 테스트한다.
4. **규모 테스트 컨테이너의 격리** — 공통 코드로 옮긴 뒤에도 클래스마다 새 컨테이너가 떠야 한다. Task 7 의 팩토리가 호출마다 새 인스턴스를 돌려준다.
5. **락 스파이가 락 동작을 바꾸지 않는가** — 스파이는 `callRealMethod` 로 실제 락을 그대로 부른다. Task 9 가 그렇게 짠다.

## File Structure

| 파일 | 과제 | 책임 |
|---|---|---|
| `connector-ldap/.../strategy/LdapDns.java` | 1 | `부모(dn)` 추가 |
| `connector-ldap/.../strategy/DitStrategy.java` | 1 | 문자열 DN 처리를 `LdapDns` 로 |
| `connector-ldap/src/test/.../DitStrategyEscapedDnTest.java` (신규) | 1 | 이스케이프된 쉼표 |
| `connector-ldap/.../strategy/RangedAttributeReader.java` | 2 | 다음 위치 = 상한 + 1 |
| `connector-ldap` 다듬기 여러 파일 | 3 | 죽은 코드·링크·템플릿 설정·주석 |
| `core/src/testFixtures/.../OrgChart.java` | 4 | `조상들` 순환 가드 |
| `core/src/test/.../OrgChartTest.java` (신규) | 4 | 순환 가드 |
| `core/src/test/.../SyncVerifierTest.java`, `ChartExpectationTest.java` | 4 | 결정성, 규칙 공백 |
| `core/src/main/.../GroupHeader.java` | 4 | `@param externalId` |
| `connector-scim/src/testFixtures/.../ScimRequestRenderer.java` | 5 | 멤버 정렬 |
| `storage-dynamodb/src/test/.../DynamoDbDirectoryStateRepositoryTest.java` | 6 | 소속 줄 `addedAt` 보존 |
| `authz-openfga/src/testFixtures/.../ScaleContainers.java` (신규) | 7 | 규모 테스트 컨테이너 생성 |
| `authz-openfga/src/testFixtures/.../ScaleVerification.java` (신규) | 7 | 두 경로 검증, 성립 확인 |
| 규모 테스트 12개 클래스 | 7·8·9 | 공통 코드 사용, 교차 검증, 깨질 수 있는 단언 |

---

### Task 1: `DitStrategy` 가 이스케이프된 쉼표에서 부모를 잃지 않는다

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapDns.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/DitStrategy.java`
- Modify: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/LdapDnsTest.java`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/DitStrategyEscapedDnTest.java`

**Interfaces:**
- Consumes: `LdapDns.대조키(String)` (이미 있음)
- Produces: `static String LdapDns.부모(String dn)` — 맨 앞 RDN 하나를 뗀 DN. 최상위면 `""`.

- [ ] **Step 1: 실패하는 전략 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/DitStrategyEscapedDnTest.java`:

```java
package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDN 값에 <b>이스케이프된 쉼표</b>가 든 디렉터리. DIT 전략은 dn 경로로 부모를 찾으므로,
 * 첫 쉼표에서 자르면 {@code ou=R\,D} 의 쉼표를 RDN 경계로 오인해 부모를 잃는다 —
 * 그 아래 전원이 소속을 <b>조용히</b> 잃는다. groupOfNames 전략에서 고친 것(2026-09-24)과
 * 같은 종류의 결함이다.
 */
class DitStrategyEscapedDnTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company

                dn: ou=R\\,D,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: R,D
                description: 연구개발

                dn: cn=Lee\\, Minho,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                cn: Lee, Minho
                sn: Lee
                uid: lee
                displayName: 이민호
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        var d = properties.getDit();
        d.setRootDn("ou=company");
        d.setOrgUnitObjectClass("organizationalUnit");
        d.setGroupIdAttribute("ou");
        d.setGroupNameAttribute("description");
        d.setUserObjectClass("inetOrgPerson");
        d.setUserIdAttribute("uid");
        d.setUserNameAttribute("displayName");
        d.setUserMailAttribute("mail");
        return properties;
    }

    @Test
    @DisplayName("이름에 쉼표가 든 OU 도 부모를 찾아 하위 조직으로 이어진다")
    void 쉼표가_든_OU가_부모에_이어진다() {
        // given
        var strategy = new DitStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — "R,D" 는 IdNormalizer 가 쉼표를 밑줄로 바꿔 "R_D" 가 된다
        assertThat(snapshot.groups().get("company").members())
                .as("첫 쉼표에서 자르면 부모를 'D,ou=company' 로 오인한다")
                .contains(MemberRef.group("R_D"));
    }

    @Test
    @DisplayName("RDN 에 쉼표가 든 직원도 부모 조직의 멤버가 된다")
    void 쉼표가_든_직원이_소속된다() {
        // given
        var strategy = new DitStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("company").members())
                .as("첫 쉼표에서 자르면 부모를 ' Minho,ou=company' 로 오인한다")
                .contains(MemberRef.user("lee"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*DitStrategyEscapedDnTest'`
Expected: 둘 다 FAIL — `company` 의 멤버에 `R_D` 도 `lee` 도 없다.

- [ ] **Step 3: `LdapDns.부모` 의 단위 테스트를 더한다**

`LdapDnsTest.java` 의 마지막 테스트 뒤에:

```java
    @Test
    @DisplayName("부모 DN 은 맨 앞 RDN 하나를 뗀 것이다 — 이스케이프된 쉼표는 경계가 아니다")
    void 부모_DN을_구한다() {
        // given, when, then
        assertThat(LdapDns.대조키(LdapDns.부모("ou=R\\,D,ou=company," + BASE)))
                .isEqualTo(LdapDns.대조키("ou=company," + BASE));
        assertThat(LdapDns.부모("ou=company"))
                .as("최상위면 부모가 없다")
                .isEmpty();
    }
```

- [ ] **Step 4: `LdapDns.부모` 를 구현한다**

`LdapDns.java` 의 `상대로` 메서드 뒤에:

```java
    /**
     * 부모 DN — 맨 앞 RDN 하나를 뗀다. 최상위면 빈 문자열이다.
     * 첫 쉼표에서 자르면 이스케이프된 쉼표({@code ou=R\,D})를 RDN 경계로 오인한다.
     */
    static String 부모(String dn) {
        LdapName name = 파싱한다(dn);
        return name.isEmpty() ? "" : name.getPrefix(name.size() - 1).toString();
    }
```

- [ ] **Step 5: `DitStrategy` 가 `LdapDns` 를 쓰게 한다**

`DitStrategy.java` 에서:
- `normalize(` 를 부르는 곳(4곳)을 모두 `LdapDns.대조키(` 로 바꾼다.
- `parentDn(` 을 부르는 곳(2곳)을 모두 `LdapDns.부모(` 로 바꾼다.
- `private static String parentDn(String dn)` 과 `private static String normalize(String dn)` 을 **지운다.**
- 쓰이지 않게 된 `import java.util.Locale;` 를 지운다.

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :connector-ldap:test`
Expected: 전부 PASS (`DitStrategyEscapedDnTest` 2개, `LdapDnsTest` 새 테스트 포함). 기존 `DitStrategyTest`·`DitStrategyDuplicateCodeTest`·`TwoStrategiesSameShapeTest` 가 깨지면 **테스트를 고치기 전에 보고한다.**

- [ ] **Step 7: 커밋한다**

```bash
git add connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapDns.java connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/DitStrategy.java connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/LdapDnsTest.java connector-ldap/src/test/java/dev/starryeye/organization/ldap/DitStrategyEscapedDnTest.java
git commit -m "fix: DIT 전략이 이스케이프된 쉼표에서 부모 조직을 잃지 않는다

첫 쉼표에서 자르면 ou=R\\,D 의 쉼표를 RDN 경계로 오인해 그 아래 전원이
소속을 조용히 잃는다. LdapDns 로 부모 DN 과 대조 키를 만든다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 범위 이어받기의 다음 위치를 표준대로 `상한 + 1` 로

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/RangedAttributeReader.java`
- Modify: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/RangedAttributeReaderTest.java`

**Interfaces:**
- Produces: `record Chunk(List<String> values, boolean 완료, int 다음시작)`. 완료면 `다음시작` 은 `-1`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`RangedAttributeReaderTest.java` 의 `이어받기_응답을_못_알아보면_던진다` 테스트 뒤에:

```java
    @Test
    @DisplayName("다음 조각은 받은 개수가 아니라 표준대로 범위 상한 + 1 에서 묻는다")
    void 다음_위치는_상한_더하기_1이다() {
        // given — 첫 조각의 범위는 0-1499 인데 값은 1,499개만 왔다. 표준(MS-ADTS)은 다음
        // 요청을 1500 에서 시작하라고 정한다. 받은 개수로 세면 1499 에서 묻는다
        List<String> 요청들 = new ArrayList<>();
        LdapOperations 서버 = mock(LdapOperations.class);
        when(서버.lookup(eq("cn=전사"), any(String[].class), any(ContextMapper.class)))
                .thenAnswer(invocation -> {
                    String 요청이름 = ((String[]) invocation.getArgument(1))[0];
                    요청들.add(요청이름);
                    Attributes attributes = new BasicAttributes();
                    attributes.put(new BasicAttribute(MEMBER));
                    if (요청이름.equals(MEMBER + ";range=0-*")) {
                        attributes.put(값이_있는(MEMBER + ";range=0-1499",
                                IntStream.range(0, 1_499).mapToObj(i -> "cn=u" + i).toArray(String[]::new)));
                    } else {
                        attributes.put(값이_있는(MEMBER + ";range=1500-*", "cn=u1500"));
                    }
                    ContextMapper<?> mapper = invocation.getArgument(2);
                    return mapper.mapFromContext(컨텍스트(attributes));
                });

        // when
        RangedAttributeReader.전부_읽는다(서버, "cn=전사", MEMBER);

        // then
        assertThat(요청들).containsExactly(MEMBER + ";range=0-*", MEMBER + ";range=1500-*");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*RangedAttributeReaderTest'`
Expected: 새 테스트만 FAIL — 두 번째 요청이 `member;range=1499-*` 다.

- [ ] **Step 3: `Chunk` 가 다음 시작 위치를 들고 다니게 한다**

`RangedAttributeReader.java` 의 `Chunk` 를 교체한다:

```java
    /**
     * @param values   이번 조각의 값들
     * @param 완료     더 받을 것이 없는가. 범위 옵션이 아예 없었거나 상한이 {@code *} 면 참
     * @param 다음시작 완료가 아니면 다음에 물을 위치 — 표준(MS-ADTS)대로 이번 범위의 상한 + 1.
     *                 완료면 쓰지 않는다({@code -1})
     */
    record Chunk(List<String> values, boolean 완료, int 다음시작) {

        Chunk {
            values = List.copyOf(values);
        }

        static Chunk 완결(List<String> values) {
            return new Chunk(values, true, -1);
        }
    }
```

`훑는다` 안의 범위 속성 분기를 교체한다:

```java
                if (matcher.matches()
                        && matcher.group("name").toLowerCase(Locale.ROOT).equals(찾는이름)) {
                    String 상한 = matcher.group("high");
                    boolean 마지막 = "*".equals(상한);
                    return new 훑은것(new Chunk(값들(attribute), 마지막,
                            마지막 ? -1 : Integer.parseInt(상한) + 1), null);
                }
```

`전부_읽는다` 의 `다음 += chunk.values().size();` 를 `다음 = chunk.다음시작();` 으로 바꾼다.

- [ ] **Step 4: 기존 테스트의 `Chunk` 생성을 새 모양에 맞춘다**

`RangedAttributeReaderTest.java` 에서:
- `new RangedAttributeReader.Chunk(List.of(), false)` → `new RangedAttributeReader.Chunk(List.of(), false, 0)`
- `new RangedAttributeReader.Chunk(List.of("cn=u"), false)` → `new RangedAttributeReader.Chunk(List.of("cn=u"), false, 1)`

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :connector-ldap:test`
Expected: 전부 PASS. `GroupOfNamesRangeContinuationTest` 의 가짜 서버는 범위를 `시작-(끝-1)` 로 적으므로 `상한 + 1` 과 받은 개수가 같아 그대로 통과해야 한다.

- [ ] **Step 6: 커밋한다**

```bash
git add connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/RangedAttributeReader.java connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/RangedAttributeReaderTest.java
git commit -m "fix: 범위 이어받기의 다음 위치를 표준대로 상한 + 1 로 정한다

받은 값의 개수로 세면 범위 표기와 개수가 다를 때 경계의 값을 다시 묻는다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `connector-ldap` 다듬기

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/RangedAttributeReader.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/PagedLdapSearch.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/MemberMatchingFailedException.java`
- Modify: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/EmbeddedLdapSupport.java`
- Modify: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/fixture/LdifRendererTest.java`
- Modify: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesRangeContinuationTest.java`

**Interfaces:** 없음(동작이 바뀌지 않는다)

- [ ] **Step 1: 죽은 `values()` 를 지운다**

`GroupOfNamesStrategy.java` 의 `private static List<String> values(Attributes attributes, String name)` 메서드 전체를 지운다. 호출자가 없고, 읽기 실패를 삼키고 읽은 만큼을 돌려주는 모양이다. 그 뒤 `NamingEnumeration` 등 **쓰이지 않게 된 import 를 지운다**(`grep -n "NamingEnumeration\|ArrayList" GroupOfNamesStrategy.java` 로 확인).

- [ ] **Step 2: 안 풀리는 자바독 링크를 고친다**

`LdapTemplates` 는 `dev.starryeye.organization.ldap` 패키지라 `strategy` 패키지에서 짧은 이름으로 링크가 풀리지 않는다. 세 곳의 `{@link LdapTemplates#한_커넥션에서}` 를 `{@link dev.starryeye.organization.ldap.LdapTemplates#한_커넥션에서}` 로 바꾼다:
- `RangedAttributeReader.java` 두 곳
- `PagedLdapSearch.java` 한 곳

- [ ] **Step 3: 테스트의 템플릿 설정을 운영과 한 곳으로**

`EmbeddedLdapSupport.java` 의 이 네 줄을:

```java
        ldapTemplate = new LdapTemplate(contextSource);
        ldapTemplate.setIgnorePartialResultException(true);
        // 프로덕션 LdapConfig 와 같은 설정. 이것이 false 여야 서버가 결과를 자른 사실이
        // 예외로 올라온다 — true 로 두면 잘린 목록이 대량 퇴사처럼 보여 실제 소속을 지운다.
        ldapTemplate.setIgnoreSizeLimitExceededException(false);
```

다음으로 바꾼다:

```java
        // 운영(LdapConfig)과 같은 자리에서 만든다. 설정을 여기 따로 적으면 운영과 검증이
        // 표류한다 — 특히 ignoreSizeLimitExceededException 이 어긋나면 잘린 목록이 대량
        // 퇴사처럼 보이는 결함을 검증이 못 본다.
        ldapTemplate = LdapTemplates.configured(contextSource);
```

`LdifRendererTest.java` 의 이 세 줄을:

```java
        LdapTemplate template = new LdapTemplate(contextSource);
        template.setIgnorePartialResultException(true);
        template.setIgnoreSizeLimitExceededException(false);
```

`LdapTemplate template = LdapTemplates.configured(contextSource);` 로 바꾸고 `import dev.starryeye.organization.ldap.LdapTemplates;` 를 더한다.

- [ ] **Step 4: `LdifRendererTest` 의 서로 반대인 주석을 정리한다**

`LdifRendererTest.java` 의 `서버를_띄우고_읽는다` 안에서 이 두 줄을 **지운다**(코드는 스키마 검사를 끄지 않는다 — 아래 "켜 둔다" 문단이 실제와 맞다):

```java
        // 빈 조직은 groupOfNames 의 member 필수 제약에 걸린다. 스키마 검사를 끄는 것은
        // 그 형태를 일부러 살려 빈 델타 경로를 태우기 위해서다.
```

- [ ] **Step 5: 범위 이어받기 테스트의 기대값을 리터럴로**

`GroupOfNamesRangeContinuationTest.java` 에서:

```java
    // 재요청은 ContextSource 의 베이스에 상대적인 DN 을 받는다(LdapConfig 가 setBase 를
    // 걸어 두기 때문) — 그래서 모킹한 DirContext 도 베이스를 뗀 형태로 호출된다.
    private static final String 상대DN_A = LdapDns.상대로(진짜DN_A, BASE_DN);
    private static final String 상대DN_B = LdapDns.상대로(진짜DN_B, BASE_DN);
```

를 다음으로 바꾼다:

```java
    // 재요청은 ContextSource 의 베이스에 상대적인 DN 을 받는다(LdapConfig 가 setBase 를
    // 걸어 두기 때문) — 그래서 모킹한 DirContext 도 베이스를 뗀 형태로 호출된다.
    // 리터럴로 적는다. 피검 함수(LdapDns.상대로)로 계산하면 그 함수가 틀릴 때 기대값도
    // 같이 틀려 이음매에서의 계약이 보이지 않는다.
    private static final String 상대DN_A = "cn=제1공장 A,ou=groups";
    private static final String 상대DN_B = "cn=제1공장:A,ou=groups";
```

- [ ] **Step 6: `MemberMatchingFailedException` 자바독에 이유를 담는다**

스택트레이스로 들어온 독자가 가드 클래스까지 가지 않아도 이유를 보게 한다. 클래스 자바독을 다음으로 교체한다:

```java
/**
 * 그룹의 {@code member} 값 중 <b>사람이 하나도</b> 우리가 읽은 직원과 대조되지 않았다.
 * {@link UnmatchedMemberGuard} 가 던진다 — 그 회차는 아무것도 쓰지 않고 실패로 끝난다.
 *
 * <p><b>대개 설정 문제다.</b> 사용자 검색 베이스가 틀렸거나, 서버가 주는 DN 의 모양이 우리가
 * 읽은 직원의 DN 과 어긋난다. 그대로 진행하면 모든 그룹이 멤버 0명으로 적재되고, 첫 적재라면
 * 삭제 가드(30%)도 걸리지 않아 아무도 권한을 받지 못한 채 성공으로 끝난다
 * (2026-09-11 코드리뷰 H).
 *
 * <p><b>재시도하지 않는다.</b> 같은 설정으로 다시 읽어도 같은 결과다
 * ({@code LdapDirectorySnapshotSource} 가 재시도에서 뺀다).
 */
```

- [ ] **Step 7: 통과를 확인한다**

Run: `./gradlew :connector-ldap:test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋한다**

```bash
git add connector-ldap/src
git commit -m "refactor: connector-ldap 의 죽은 코드와 낡은 설명을 치운다

죽은 values() 삭제, 풀리지 않는 자바독 링크, 테스트 두 곳의 템플릿
설정을 운영과 한 곳으로, 서로 반대인 스키마 주석 정리.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: core 하네스 — 순환 가드, 결정성, 규칙 공백

**Files:**
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/OrgChart.java:53-65`
- Create: `core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartTest.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/fixture/SyncVerifierTest.java:103,259`
- Modify: `core/src/test/java/dev/starryeye/organization/core/fixture/ChartExpectationTest.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/GroupHeader.java`

**Interfaces:** `OrgChart.조상들(String)` 의 시그니처는 그대로다. 순환이면 `IllegalStateException`.

- [ ] **Step 1: 실패하는 순환 가드 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartTest.java`:

```java
package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrgChartTest {

    @Test
    @Timeout(5)
    @DisplayName("순환이 든 조직도에서 조상을 물으면 끝없이 돌지 않고 경로를 담아 즉시 던진다")
    void 순환이면_조상_질의가_즉시_실패한다() {
        // given — A 가 B 를, B 가 A 를 하위로 갖는다. 순환 시나리오(L16/S16)가 만드는 모양이다
        var chart = new OrgChart(new DirectorySnapshot(Map.of(), Map.of(
                "A", new DirectoryGroup("A", null, "A", Set.of(MemberRef.group("B"))),
                "B", new DirectoryGroup("B", null, "B", Set.of(MemberRef.group("A"))))), null);

        // when, then — 가드가 없으면 10분 타임아웃이 되고, 원인이 보이지 않는다
        assertThatThrownBy(() -> chart.조상들("A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환")
                .hasMessageContaining("A");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*OrgChartTest'`
Expected: FAIL — 5초 타임아웃(`TimeoutException`).

- [ ] **Step 3: 순환 가드를 넣는다**

`OrgChart.java` 의 `조상들` 을 교체한다:

```java
    /**
     * 조직 {@code orgCode} 의 조상들을 가까운 순으로. 롤업 검증이 이 체인을 탄다.
     *
     * <p><b>순환이면 즉시 던진다.</b> 순환 시나리오(L16/S16)는 조직도에 순환을 일부러 넣는데,
     * 가드가 없으면 이 루프가 끝나지 않아 결과가 아니라 타임아웃으로 나타난다.
     */
    public List<String> 조상들(String orgCode) {
        List<String> chain = new ArrayList<>();
        Set<String> 지나온것 = new HashSet<>(List.of(orgCode));
        String current = orgCode;
        while (true) {
            String parent = 부모(current);
            if (parent == null) {
                return chain;
            }
            if (!지나온것.add(parent)) {
                throw new IllegalStateException("조직도에 순환이 있습니다: " + orgCode
                        + (chain.isEmpty() ? "" : " → " + String.join(" → ", chain))
                        + " → " + parent);
            }
            chain.add(parent);
            current = parent;
        }
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*OrgChartTest'`
Expected: PASS

- [ ] **Step 5: `SyncVerifierTest` 의 고르기를 결정적으로**

`Set` 의 순회 순서는 JVM 실행마다 달라진다(`Set.copyOf` 의 salt). 두 곳을 정렬해서 고른다.

103행:

```java
        줄인것.remove(원본.members().iterator().next());
```

→

```java
        // 순회 순서가 실행마다 달라지지 않게 정렬해서 고른다
        줄인것.remove(원본.members().stream()
                .min(java.util.Comparator.comparing(MemberRef::id))
                .orElseThrow());
```

259행:

```java
        String 아래조직 = chart.자손들(chart.직속조직(직원)).iterator().next();
```

→

```java
        // 순회 순서가 실행마다 달라지지 않게 정렬해서 고른다
        String 아래조직 = chart.자손들(chart.직속조직(직원)).stream().sorted().findFirst().orElseThrow();
```

- [ ] **Step 6: `ChartExpectationTest` 의 규칙 공백을 채운다**

`순환은_거부한다` 테스트 뒤에:

```java
    @Test
    @DisplayName("자기 자신을 하위로 갖는 조직도 순환이다")
    void 자기_루프는_순환이다() {
        // given
        조직("TEAM", MemberRef.user("a"), MemberRef.group("TEAM"));

        // when, then
        assertThatThrownBy(() -> ChartExpectation.of(조직도()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("순환");
    }

    @Test
    @DisplayName("순환 메시지는 고리의 경로를 담는다 — 어느 간선이 문제인지 보여야 고친다")
    void 순환_메시지는_경로를_담는다() {
        // given — CORP → DEV → TEAM → CORP
        조직("TEAM", MemberRef.user("a"), MemberRef.group("CORP"));

        // when, then — 조직을 정렬된 순서로 훑으므로 CORP 에서 출발한다
        assertThatThrownBy(() -> ChartExpectation.of(조직도()))
                .hasMessageContaining("CORP → DEV → TEAM → CORP");
    }
```

`비활성_롤업` 테스트 뒤에:

```java
    @Test
    @DisplayName("활성 직원의 롤업 음성에는 직속 조직의 자손이 들어간다")
    void 활성_직원의_자손_음성() {
        // given — e 는 DEV 직속 활성 직원. 아래에 TEAM·TEAM2 가 있다
        직원("e", true);
        조직("DEV", MemberRef.group("TEAM"), MemberRef.group("TEAM2"),
                MemberRef.user("b"), MemberRef.user("e"));

        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — 자손 TEAM·TEAM2 와 형제 가지 MGT. 권한이 아래로 새면 여기서 잡힌다
        assertThat(기대.롤업음성("e")).containsExactlyInAnyOrder(
                member("e", "TEAM"), member("e", "TEAM2"), member("e", "MGT"));
    }
```

`다중_부모` 테스트 뒤에:

```java
    @Test
    @DisplayName("부모가 둘인 조직의 형제 가지 음성은 두 부모 쪽 모두다")
    void 다중_부모의_형제_음성() {
        // given — X 는 TEAM 과 MGT 양쪽의 하위. TEAM 아래에 형제 T3, MGT 아래에 형제 M2
        직원("x", true);
        조직("X", MemberRef.user("x"));
        조직("T3");
        조직("M2");
        조직("TEAM", MemberRef.user("a"), MemberRef.group("X"), MemberRef.group("T3"));
        조직("MGT", MemberRef.user("c"), MemberRef.group("X"), MemberRef.group("M2"));

        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — 한쪽 부모만 보는 결함이면 둘 중 하나가 빠진다
        assertThat(기대.롤업음성("x")).containsExactlyInAnyOrder(member("x", "T3"), member("x", "M2"));
    }
```

- [ ] **Step 7: `GroupHeader` 의 `@param` 을 채운다**

`GroupHeader.java` 의 `@param id ...` 줄 다음에:

```java
 * @param externalId 원천 디렉터리가 준 식별자(LDAP 은 DN). 튜플에 쓰지 않는다
```

- [ ] **Step 8: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: 전부 PASS. `ChartExpectationTest` 의 새 테스트 넷은 **이미 있는 규칙을 못박는 것**이라 처음부터 통과한다. 통과하지 않으면 규칙이 서술과 다른 것이니 테스트를 고치기 전에 보고한다.

- [ ] **Step 9: 커밋한다**

```bash
git add core/src
git commit -m "test: 조상 질의의 순환 가드와 하네스의 결정성을 더한다

순환이 든 조직도에서 조상들은 타임아웃이 아니라 경로를 담은 예외로 끝난다.
SyncVerifierTest 는 Set 순회 순서에 기대지 않고 정렬해 고른다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: SCIM 렌더러가 멤버를 정렬해서 내보낸다

**Files:**
- Modify: `connector-scim/src/testFixtures/java/dev/starryeye/organization/scim/fixture/ScimRequestRenderer.java:169-177`
- Modify: `connector-scim/src/test/java/dev/starryeye/organization/scim/fixture/ScimRequestRendererTest.java`

**Interfaces:** 없음

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimRequestRendererTest.java` 의 `조직_본문이_왕복한다` 뒤에:

```java
    @Test
    @DisplayName("조직 본문의 멤버는 아이디 순으로 나간다 — 시드 파일 바이트가 실행마다 같아야 한다")
    void 멤버를_정렬해서_내보낸다() {
        // given — DirectoryGroup 은 멤버를 Set.copyOf 로 담아 순회 순서가 JVM 실행마다 다르다.
        // 20명이면 정렬 없이 우연히 정렬된 순서가 나올 일은 없다
        List<String> 아이디들 = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "u%02d".formatted(i)).toList();
        var group = new DirectoryGroup("DEV", null, "개발",
                아이디들.stream().map(MemberRef::user).collect(java.util.stream.Collectors.toSet()));

        // when
        var body = (ScimGroup) ScimRequestRenderer.조직생성(group).body();

        // then
        assertThat(body.members()).extracting(ScimMember::value).containsExactlyElementsOf(아이디들);
    }
```

`import dev.starryeye.organization.scim.dto.ScimMember;` 가 없으면 더한다(`ScimGroup`·`DirectoryGroup`·`MemberRef`·`List` import 도 확인).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimRequestRendererTest'`
Expected: 새 테스트 FAIL — 순서가 다르다.

- [ ] **Step 3: 정렬해서 내보낸다**

`ScimRequestRenderer.scimGroup` 의 멤버 줄을:

```java
                group.members().stream().map(ScimRequestRenderer::scimMember).toList(),
```

다음으로 바꾼다:

```java
                // 아이디 순으로 — Set 의 순회 순서는 JVM 실행마다 달라, 그대로 두면 같은 조직도로
                // 만든 시드 파일의 바이트가 매번 다르다
                group.members().stream()
                        .sorted(java.util.Comparator.comparing(MemberRef::id)
                                .thenComparing(MemberRef::type))
                        .map(ScimRequestRenderer::scimMember)
                        .toList(),
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add connector-scim/src
git commit -m "test: SCIM 렌더러가 조직 멤버를 정렬해서 내보낸다

Set 의 순회 순서가 JVM 실행마다 달라 시드 파일 바이트가 매번 달랐다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 소속 줄의 `addedAt` 보존을 못박는다

**Files:**
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:** 없음

- [ ] **Step 1: 테스트를 쓴다**

`기존_멤버의_addedAt은_보존된다` 테스트 뒤에:

```java
    /** 소속 줄(멤버 자신의 파티션)의 addedAt 을 직접 읽는다. */
    private String 소속줄_addedAt(String groupId, MemberRef member) {
        var response = client.getItem(builder -> builder
                .tableName(properties.getTableName())
                .key(java.util.Map.of(
                        Keys.PK, Attrs.s(Keys.memberPk(member)),
                        Keys.SK, Attrs.s(Keys.belongsToSk(groupId))))).join();
        return response.item().get("addedAt").s();
    }

    @Test
    @DisplayName("이미 소속된 멤버의 소속 줄 addedAt 도 다시 동기화해도 최초 합류 시각 그대로다")
    void 기존_멤버의_소속줄_addedAt도_보존된다() {
        // given — kim 이 1월 1일에 합류했다
        var kim = MemberRef.user("kim");
        repository.saveGroup(조직("DEV001", "개발본부", kim)).block();
        String 최초합류 = 소속줄_addedAt("DEV001", kim);

        // when — 한 달 뒤, 다른 사람이 들어오면서 같은 조직이 다시 저장된다
        clock.앞으로(Duration.ofDays(31));
        repository.saveGroup(조직("DEV001", "개발본부", kim, MemberRef.user("park"))).block();

        // then — 멤버 줄과 짝을 이루는 소속 줄도 덮이지 않아야 한다
        assertThat(소속줄_addedAt("DEV001", kim)).isEqualTo(최초합류);
    }
```

- [ ] **Step 2: 통과를 확인한다 — 이미 있는 동작을 못박는 테스트다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
Expected: PASS

- [ ] **Step 3: 변이로 테스트가 잡는지 확인한다**

`DynamoDbDirectoryStateRepository.saveGroup` 에서 **소속 줄만** 모든 멤버에 대해 다시 쓰도록 임시로 바꾼다 — 마지막 `.then(Flux.fromIterable(새로온멤버)...)` 뒤에 다음을 덧붙인다:

```java
                            .then(Flux.fromIterable(group.members())
                                    .flatMap(member -> putItem(belongsToItem(member, group.id())))
                                    .then())
```

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
Expected: 새 테스트만 FAIL. **변이를 되돌리고** `git diff storage-dynamodb/src/main` 이 비어 있는지 확인한 뒤 다시 돌려 PASS 를 본다.

- [ ] **Step 4: 커밋한다**

```bash
git add storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java
git commit -m "test: 소속 줄의 addedAt 도 재동기화에서 보존됨을 못박는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 규모 테스트의 공통 코드를 모은다 (동작 변화 없음)

**Files:**
- Modify: `authz-openfga/build.gradle`
- Create: `authz-openfga/src/testFixtures/java/dev/starryeye/organization/authz/fixture/ScaleContainers.java`
- Create: `authz-openfga/src/testFixtures/java/dev/starryeye/organization/authz/fixture/ScaleVerification.java`
- Modify: 규모 테스트 12개 — `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/` 의 `AdminQueryScaleTest`, `DitScaleSyncTest`, `LdapDeletionGuardScaleTest`, `LdapInterruptedSyncScaleTest`, `LdapPagingScaleTest`, `LdapScaleScenarioTest`, `LdapScaleSyncCostTest` / `app-scim/src/test/java/dev/starryeye/organization/scim/app/` 의 `ScimLimitsAndRecoveryScaleTest`, `ScimProvisioningOrderScaleTest`, `ScimRebuildLockScaleTest`, `ScimScaleScenarioTest`, `ScimScaleSyncCostTest`

**Interfaces:**
- Produces:
  - `ScaleContainers.openFga()` / `ScaleContainers.dynamoDb()` — 호출마다 **새** `GenericContainer<?>`
  - `ScaleContainers.주소를_등록한다(BiConsumer<String, Supplier<Object>> 등록, GenericContainer<?> openFga, GenericContainer<?> dynamoDb)`
  - `ScaleVerification.하네스로_검증한다(DirectoryStateRepository, RelationTupleChecker, OrgChart)`
  - `ScaleVerification.두_경로로_검증한다(DirectoryStateRepository, RelationTupleChecker, StoreBootstrapper, OrgChart)` 와 `ChartExpectation` 을 받는 오버로드
  - `ScaleVerification.성립하는가(RelationTupleChecker, RelationTuple)`

- [ ] **Step 1: testFixtures 에 Testcontainers 를 연다**

`authz-openfga/build.gradle` 의 `testFixturesApi testFixtures(project(':core'))` 다음 줄에:

```groovy
    testFixturesApi libs.testcontainers.junit
```

- [ ] **Step 2: 컨테이너 팩토리를 만든다**

`ScaleContainers.java`:

```java
package dev.starryeye.organization.authz.fixture;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 규모 테스트가 띄우는 컨테이너. 열두 클래스가 똑같은 정의를 한 벌씩 들고 있었다.
 *
 * <p><b>인스턴스는 호출마다 새로 만든다 — 클래스끼리 공유하지 않는다.</b> DynamoDB 테이블
 * 이름과 OpenFGA store 이름이 고정이라, 공유하면 한 클래스가 남긴 상태가 다음 클래스의
 * 기준선이 된다. 클래스마다 {@code @Container static final} 필드에 이 팩토리의 결과를 담는다.
 */
public final class ScaleContainers {

    private ScaleContainers() {
    }

    public static GenericContainer<?> openFga() {
        return new GenericContainer<>(DockerImageName.parse("openfga/openfga:v1.10.2"))
                .withCommand("run")
                .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));
    }

    public static GenericContainer<?> dynamoDb() {
        return new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
                .withExposedPorts(8000)
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");
    }

    /**
     * 두 컨테이너의 주소를 스프링 설정으로 넘긴다. {@code DynamicPropertyRegistry::add} 를
     * 그대로 넘긴다 — 이 모듈이 spring-test 에 묶이지 않게 함수로 받는다.
     */
    public static void 주소를_등록한다(BiConsumer<String, Supplier<Object>> 등록,
                                GenericContainer<?> openFga, GenericContainer<?> dynamoDb) {
        등록.accept("openfga.api-url",
                () -> "http://" + openFga.getHost() + ":" + openFga.getMappedPort(8080));
        등록.accept("dynamodb.endpoint",
                () -> "http://" + dynamoDb.getHost() + ":" + dynamoDb.getMappedPort(8000));
    }
}
```

- [ ] **Step 3: 검증 도우미를 만든다**

`ScaleVerification.java`:

```java
package dev.starryeye.organization.authz.fixture;

import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.ChartExpectation;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.RollupSampling;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.fixture.VerificationResult;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;

import java.time.Duration;

/**
 * 규모 테스트들이 한 벌씩 들고 있던 검증 도우미.
 *
 * <p><b>두 경로로 묻는 이유.</b> 하네스는 {@code RelationTupleChecker} 포트를 타고, 프로브는
 * OpenFGA SDK 를 그대로 쓴다. 어댑터에 결함이 있으면 하네스는 그 결함에 같이 속는다 — 같은
 * 사실을 서로 다른 경로로 두 번 물어 답이 갈리면, 갈렸다는 것 자체가 결함이다.
 */
public final class ScaleVerification {

    private ScaleVerification() {
    }

    /** 하네스로만 묻는다. 조회 API·시간 측정처럼 교차 검증이 목적이 아닌 클래스용. */
    public static void 하네스로_검증한다(DirectoryStateRepository state, RelationTupleChecker checker,
                                 OrgChart 기대) {
        확인한다("하네스", new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10)));
    }

    public static void 두_경로로_검증한다(DirectoryStateRepository state, RelationTupleChecker checker,
                                  StoreBootstrapper bootstrapper, OrgChart 기대) {
        두_경로로_검증한다(state, checker, bootstrapper, ChartExpectation.of(기대));
    }

    public static void 두_경로로_검증한다(DirectoryStateRepository state, RelationTupleChecker checker,
                                  StoreBootstrapper bootstrapper, ChartExpectation 기대) {
        확인한다("하네스", new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10)));
        확인한다("OpenFGA 직접 질의", new OpenFgaProbe(bootstrapper)
                .직접_대조한다(기대, RollupSampling.기본값().표본을_고른다(기대.chart())));
    }

    public static boolean 성립하는가(RelationTupleChecker checker, RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }

    private static void 확인한다(String 경로, VerificationResult 결과) {
        if (결과 == null) {
            throw new AssertionError(경로 + " 가 결과를 돌려주지 않았습니다");
        }
        if (결과.어긋났는가()) {
            throw new AssertionError(경로 + ": " + 결과.요약());
        }
    }
}
```

`SyncVerifier.검증한다(ChartExpectation)` 이 있는지 확인한다(`core/src/testFixtures/.../SyncVerifier.java:63`). `VerificationResult` 에 `어긋났는가()`·`요약()` 이 있는지도 확인한다.

- [ ] **Step 4: 컴파일을 확인한다**

Run: `./gradlew :authz-openfga:compileTestFixturesJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 열두 클래스의 컨테이너 정의를 바꾼다**

각 클래스에서 이 두 필드를:

```java
    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");
```

다음으로 바꾼다:

```java
    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();
```

그리고 `@DynamicPropertySource` 메서드 안의 이 두 등록을:

```java
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
```

`ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);` 로 바꾼다. **그 밖의 `registry.add` 줄(`ldap.url`, `ldap.page-size`, `dynamodb.lock-ttl` 등)은 그대로 둔다.**

- [ ] **Step 6: 열두 클래스의 도우미 본문을 바꾼다**

메서드 이름과 호출부는 그대로 두고 **본문만** 공통 코드로 바꾼다.

`성립하는가` 가 있는 클래스:

```java
    private boolean 성립하는가(RelationTuple tuple) {
        return ScaleVerification.성립하는가(checker, tuple);
    }
```

`검증한다` 가 **하네스 + 프로브 두 경로**인 클래스(`LdapScaleScenarioTest`, `ScimLimitsAndRecoveryScaleTest` 의 기본 `검증한다()`, `ScimProvisioningOrderScaleTest`, `ScimRebuildLockScaleTest`, `ScimScaleScenarioTest`):

```java
    private void 검증한다() {
        ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 기대);
    }
```

`ScimProvisioningOrderScaleTest` 의 `검증한다(ChartExpectation 기대값)` 는 `ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 기대값);` 로.

`검증한다` 가 **하네스만**인 클래스(`AdminQueryScaleTest`, `DitScaleSyncTest`, `LdapDeletionGuardScaleTest`, `LdapInterruptedSyncScaleTest`) — **이 과제에서는 하네스만 유지한다**(교차 검증은 Task 8):

```java
    private void 검증한다() {
        ScaleVerification.하네스로_검증한다(state, checker, 기대);
    }
```

**위 모양과 다른 검증은 건드리지 않는다** — 시간 측정 클래스 둘(`LdapScaleSyncCostTest`, `ScimScaleSyncCostTest`)의 측정 후 검증, `ScimLimitsAndRecoveryScaleTest` 의 `검증한다(뒤집힌결과)` 같은 변종은 그대로 두고 보고서에 적는다.

- [ ] **Step 7: 쓰이지 않게 된 import 를 지운다**

각 클래스에서 `GenericContainer` 는 남고, `Wait`·`DockerImageName`·`SyncVerifier`·`OpenFgaProbe`·`RollupSampling`·`Duration` 등은 **쓰이는지 확인하고** 안 쓰이면 지운다. `ScaleContainers`·`ScaleVerification` import 를 더한다.

- [ ] **Step 8: 컴파일과 앱 모듈의 빠른 테스트를 확인한다**

Run: `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app-ldap:test :app-scim:test`
Expected: 전부 PASS (규모 테스트는 이 작업에 포함되지 않는다)

그리고 **규모 테스트 클래스 하나**로 실제 동작을 확인한다:

Run: `./gradlew :app-ldap:scaleTest --tests '*DitScaleSyncTest'`
Expected: PASS

- [ ] **Step 9: 커밋한다**

```bash
git add authz-openfga app-ldap/src/test app-scim/src/test
git commit -m "refactor: 규모 테스트의 컨테이너 정의와 검증 도우미를 한 곳으로 모은다

열두 클래스가 한 벌씩 들고 있던 컨테이너 정의와 검증 도우미를
authz-openfga testFixtures 로 옮긴다. 컨테이너 인스턴스는 클래스마다
따로 둔다 — 테이블과 store 이름이 고정이라 공유하면 상태가 섞인다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

> **컨트롤러:** 이 과제 뒤에 `./gradlew cleanScaleTest scaleTest` 를 한 번 돌린다. 구조를 크게 바꾼 과제라 뒤 과제들과 섞이기 전에 확인한다.

---

### Task 8: LDAP 규모 테스트 세 곳에 교차 검증을 붙인다

**Files:**
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/DitScaleSyncTest.java`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapDeletionGuardScaleTest.java`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java`

**Interfaces:**
- Consumes: `ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 기대)` (Task 7)

- [ ] **Step 1: 세 클래스를 두 경로 검증으로 바꾼다**

세 클래스 각각에:
- `@Autowired StoreBootstrapper bootstrapper;` 를 다른 `@Autowired` 필드 옆에 더한다(`import dev.starryeye.organization.authz.StoreBootstrapper;`).
- `검증한다()` 본문을 `ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 기대);` 로 바꾼다.

- [ ] **Step 2: 새 경로가 실제로 타는지 확인한다 — 배선 확인**

`ScaleVerification.두_경로로_검증한다(…, ChartExpectation)` 의 두 번째 `확인한다(…)` 앞에 임시로 `throw new AssertionError("프로브 경로를 탔다");` 를 넣는다.

Run: `./gradlew :app-ldap:scaleTest --tests '*DitScaleSyncTest'`
Expected: FAIL — 메시지 "프로브 경로를 탔다". **되돌리고** `git diff authz-openfga` 가 비었는지 확인한다.

- [ ] **Step 3: 세 클래스를 돌린다**

Run (하나씩, 순서대로):
- `./gradlew :app-ldap:scaleTest --tests '*DitScaleSyncTest'`
- `./gradlew :app-ldap:scaleTest --tests '*LdapDeletionGuardScaleTest'`
- `./gradlew :app-ldap:scaleTest --tests '*LdapInterruptedSyncScaleTest'`

Expected: 모두 PASS. 프로브가 어긋남을 보고하면 **그것은 새로 드러난 결함**이다 — 테스트를 느슨하게 고치지 말고 메시지 그대로 보고한다.

- [ ] **Step 4: 커밋한다**

```bash
git add app-ldap/src/test
git commit -m "test: LDAP 규모 테스트 세 곳도 OpenFGA 를 직접 질의해 교차 검증한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 깨질 수 있는 단언과 낡은 숫자를 고친다

**Files:**
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRebuildLockScaleTest.java`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimLimitsAndRecoveryScaleTest.java:65,212-223`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapScaleScenarioTest.java:186-192`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapPagingScaleTest.java:113`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminQueryScaleTest.java:49`

**Interfaces:** 없음

- [ ] **Step 1: 재적재 락 테스트 — `sleep` 과 벽시계를 락 스파이로**

`ScimRebuildLockScaleTest.java` 에 필드를 더한다:

```java
    /**
     * 실제 락을 감싼 스파이. 동작은 그대로다(callRealMethod) — "재적재가 락을 잡았다" 를
     * 기다리고 "리스를 갱신했다" 를 직접 확인하려고 쓴다. 운영 코드에 지표를 더하지 않는다.
     */
    @MockitoSpyBean MutationLock lock;
```

(`import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;`, `import dev.starryeye.organization.core.port.MutationLock;`, `import dev.starryeye.organization.core.model.LockLease;` — `LockLease` 의 실제 패키지를 `MutationLock.java` 의 import 에서 확인한다.)

`S18b_재적재_중_쓰기와_리스` 의 `given` 부터 `Thread.sleep(700);` 까지를 교체한다:

```java
        // given — 앞선 기준 적재에서 쓰기가 renew 를 불렀을 수 있다. 이 시나리오의 호출만 센다
        clearInvocations(lock);

        // 재적재가 락을 잡는 순간을 알린다. sleep 으로 "아마 잡았겠지" 를 바라지 않는다 —
        // 곧바로 쓰기를 두드리면 쓰기가 먼저 락을 쥐고 재적재가 409 로 튕긴다
        CountDownLatch 재적재가_락을_잡았다 = new CountDownLatch(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Mono<LockLease> 실제 = (Mono<LockLease>) invocation.callRealMethod();
            return invocation.getArgument(0) == MutationLock.LockPurpose.REBUILD
                    ? 실제.doOnSuccess(lease -> 재적재가_락을_잡았다.countDown())
                    : 실제;
        }).when(lock).acquire(any());

        long t0 = System.currentTimeMillis();
        CompletableFuture<Integer> 재적재 = CompletableFuture.supplyAsync(() ->
                client.mutate().responseTimeout(Duration.ofMinutes(20)).build()
                        .post().uri("/admin/sync/rebuild?mode=tuples").exchange()
                        .returnResult(Void.class).getStatus().value());
        assertThat(재적재가_락을_잡았다.await(1, TimeUnit.MINUTES))
                .as("재적재가 1분 안에 락을 잡지 못했다").isTrue();
```

`then` 의 벽시계 단언을:

```java
        assertThat(소요)
                .as("재적재가 리스 TTL(2초)보다 빨리 끝나면 하트비트가 한 번도 필요하지 않다")
                .isGreaterThan(2_000L);
```

다음으로 바꾼다:

```java
        // 리스 갱신이 실제로 일했다. 쓰기는 델타가 있을 때만 renew 를 부르고(설계 §4.7) 여기서
        // 두드린 쓰기는 이미 활성인 직원에게 active:true 를 보내 델타가 없다 — 그러므로 이 호출은
        // 재적재의 하트비트다
        verify(lock, atLeastOnce()).renew(any());
```

`System.out.printf` 의 소요 시간 출력은 참고용으로 남긴다. `import static org.mockito.Mockito.*` 계열(`doAnswer`, `clearInvocations`, `verify`, `atLeastOnce`)과 `ArgumentMatchers.any`, `java.util.concurrent.CountDownLatch`, `java.util.concurrent.TimeUnit`, `reactor.core.publisher.Mono` 를 더한다.

- [ ] **Step 2: 실행 이력을 순서가 아니라 트리거로 찾는다**

`ScimLimitsAndRecoveryScaleTest.java` 의:

```java
        assertThat(runs).isNotEmpty();
        JsonNode 최근 = runs.get(0);
        assertThat(최근.get("source").asText()).isEqualTo("SCIM");
        assertThat(최근.get("trigger").asText()).isEqualTo("REBUILD");
        assertThat(최근.get("writtenCount").asInt()).isGreaterThan(5_000);
```

를 다음으로 바꾼다:

```java
        // 목록의 순서에 기대지 않고 트리거로 찾는다
        JsonNode 재적재 = java.util.stream.StreamSupport.stream(runs.spliterator(), false)
                .filter(run -> "REBUILD".equals(run.get("trigger").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("재적재 이력이 없다: " + runs));
        assertThat(재적재.get("source").asText()).isEqualTo("SCIM");
        // 재적재는 store 를 비우고 상태가 요구하는 튜플을 전부 다시 쓴다 — 픽스처에서 유도한다
        assertThat(재적재.get("writtenCount").asInt())
                .isEqualTo(ChartExpectation.of(기대).있어야할튜플().size());
```

`writtenCount` 가 기대 튜플 수와 다르면 **테스트를 느슨하게 고치지 말고 보고한다.** (`ChartExpectation` import 확인.)

- [ ] **Step 3: 고아 튜플의 조직을 랜드마크로**

같은 파일 65행의 `RelationTuple.directMember("ghost.user", "DEV5_0");` 를 `RelationTuple.directMember("ghost.user", 기대.landmarks().대상팀());` 로 바꾼다. `기대` 필드가 `고아` 필드보다 **위에** 선언돼 있는지 확인한다(정적 초기화 순서).

- [ ] **Step 4: L4 가 겸직 직원을 명시적으로 뺀다**

`LdapScaleScenarioTest.java` 의 `L4_직원_삭제` 에서 `.filter(id -> !랜드마크직원들().contains(id))` 다음 줄에:

```java
                // 겸직 직원은 뺀다. 이 파트에서만 지우는 것이라, 다른 조직에도 속한 사람을 고르면
                // 튜플 계산이 이 시나리오의 가정과 달라진다 — 지금은 우연히 없을 뿐이다
                .filter(id -> 기대.직속조직들(id).size() == 1)
```

- [ ] **Step 5: 낡은 주석 숫자를 지운다**

`LdapPagingScaleTest.java:113` 의:

```java
        // 1,000명만 읽고 나머지 4,024명을 퇴사로 판정해 지우는 것이 이 방어선이 막는 일이다.
```

→

```java
        // 상한만큼만 읽고 나머지 전원을 퇴사로 판정해 지우는 것이 이 방어선이 막는 일이다.
```

`AdminQueryScaleTest.java:49` 의 `500명짜리 조직을 100개씩 끊어 읽을 때` 를 `큰 조직을 100개씩 끊어 읽을 때` 로 바꾼다(100 은 테스트가 쓰는 페이지 크기인지 확인하고, 아니면 그 숫자도 지운다).

- [ ] **Step 6: 컴파일과 해당 규모 테스트를 확인한다**

Run: `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: BUILD SUCCESSFUL

Run (하나씩):
- `./gradlew :app-scim:scaleTest --tests '*ScimRebuildLockScaleTest'`
- `./gradlew :app-scim:scaleTest --tests '*ScimLimitsAndRecoveryScaleTest'`
- `./gradlew :app-ldap:scaleTest --tests '*LdapScaleScenarioTest'`

Expected: 모두 PASS

- [ ] **Step 7: 커밋한다**

```bash
git add app-ldap/src/test app-scim/src/test
git commit -m "test: 규모 테스트의 깨질 수 있는 단언과 낡은 숫자를 고친다

재적재 락 테스트는 sleep 과 벽시계 대신 락 스파이로 '잡았다' 와
'갱신했다' 를 직접 확인한다. 실행 이력은 순서가 아니라 트리거로 찾고,
L4 는 겸직 직원을 명시적으로 뺀다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 머지 전 (컨트롤러)

- [ ] `./gradlew cleanTest test`
- [ ] `./gradlew cleanScaleTest scaleTest` — 소요 시간을 스펙 §8 에 적는다(이전 실측: ④ 머지 전 12분 10초)
- [ ] 최종 리뷰 → PR → main 머지

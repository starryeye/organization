# 점검 ⑤-2 SCIM 요청 해석 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SCIM 요청을 표준대로 읽도록 다음을 고친다.
- 저장하지 않는 RFC 속성이 path 로 와도 비활성화를 막지 않는다(M5·M18).
- 조직 PATCH 가 `externalId`(④-1 이월)와 Group URN 접두(S9)를 받는다.
- 본문 속성 이름은 대소문자 없이(S6), `active` 문자열은 표준 신호만(S8) 받는다.
- POST 201 에 `Location` 을 단다(S1).
- 문서로 S5 앞부분·S12 를 정리한다.

**Architecture:**
- connector-scim 에 RFC 7643 속성 표(`ScimRfcAttributes`)를 둔다. `ScimPatchApplier` 는 적용하지 못한 path 를 이 표로 가른다 — RFC 가 정의한 것이면 받아서 버리고, 모르는 것만 400 이다. 버린 이름은 관찰자(`IgnoredAttributeObserver`)를 거쳐 app-scim 의 Micrometer 카운터로 나간다.
- 조직 `externalId` 는 core `GroupChange.reidentified` 로 PUT 이 쓰던 중복 판정 길을 그대로 탄다.
- 나머지는 DTO 애노테이션, 값 해석 한 곳, 응답 헤더 한 줄이다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor, Jackson 2, Micrometer, JUnit 5, AssertJ, WebTestClient, Testcontainers(DynamoDB Local·OpenFGA).

**Spec:** `docs/superpowers/specs/2026-10-06-scim-request-interpretation-design.md`

## Global Constraints

- **테스트 표지.** 모든 테스트에 `// given` / `// when` / `// then` 표지를 둔다. `// when, then` 처럼 합쳐도 된다. 계획의 코드에 빠져 있으면 더한다.
- **이름과 글.** 이름·주석·메시지는 한국어로, 주변 코드처럼 평서문으로 쓴다. `@DisplayName` 은 한국어 문장이다. 클래스 이름은 영어다.
- **커밋.** 제목은 한국어로 쓰고, 빈 줄 다음에 자기 하네스가 주는 `Co-Authored-By:` 줄을 단다(heredoc). 커밋할 때마다 `git push` 한다.
- **Gradle.** 한 번에 하나씩, 포그라운드로만 돌린다. 과제가 정한 모듈 테스트만 돌린다. `scaleTest` 와 전체 `test` 는 컨트롤러 몫이다.
- **금지.**
  - 서브에이전트를 띄우지 않는다.
  - 파일시스템 전체를 검색하지 않는다.
  - 백그라운드 프로세스를 쓰지 않는다.
  - `git stash` 를 쓰지 않는다.
  - 파일은 경로로 지정해 스테이징한다.
- **테스트 수.** 결과 XML 에서 옮긴다. 어림하지 않는다.
- **메트릭 태그.** `scim.patch.ignored` 의 `attribute` 태그는 `ScimRfcAttributes` 의 정규 이름이거나 `"other"` 뿐이다. 요청 문자열을 태그에 그대로 넣지 않는다.
- **로그.** 버린 속성의 **값**(전화번호·주소)을 로그에 남기지 않는다. 이름만 남긴다.
- **경로 없는 값의 모르는 키.** 지금처럼 무시한다(400 으로 바꾸지 않는다). 설계 §3.4.
- **저장소 읽기.** 새로 더하지 않는다(실제 규모 10만 명+). 조직 `externalId` 를 바꿀 때의 GSI3 조회는 이미 있는 길이다.

## Review Focus

1. **한 요청에 섞인 연산.** 저장하지 않는 RFC path(`phoneNumbers[…]`)와 저장하는 path(`displayName`), 경로 없는 `{"active":false}` 가 한 요청에 섞이면, 저장하는 것은 모두 반영되고 버린 것만 기록된다 — Task 3 테스트.
2. **저장하는 복수 속성의 다른 하위 속성.** `emails[type eq "work"].display` 는 버리고, 저장된 work 이메일은 그대로다 — Task 3 테스트.
3. **조직 경로 없는 값의 URN 접두 붙은 `members`.** `type` 없는 멤버는 지금처럼 현재상태로 판정된다(모으는 쪽과 적용하는 쪽이 같은 이름을 본다) — Task 4 테스트.
4. **`externalId` 를 비운 뒤.** 조직 PATCH 로 `externalId` 를 비우면(remove), 다른 조직이 그 옛 값으로 만들어질 수 있다 — Task 1 테스트.
5. **경로 없는 값의 커스텀 확장 키.** 메트릭 태그는 키 문자열이 아니라 `other` 다 — Task 5 테스트.

---

### Task 1: core — `GroupChange.reidentified`

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/GroupChange.java` (자바독 `@param reidentifies`, 메서드 하나 더함)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCreateUniquenessTest.java` (테스트 셋 더함)

**Interfaces:**
- Produces: `public GroupChange reidentified(String newExternalId)` — `reidentifies=true`, `externalId=newExternalId`(null 이면 비운다). 나머지 칸(`renames`, `displayName`, `base`, `ops`)은 그대로다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`IncrementalSyncCreateUniquenessTest` 끝에 더한다(기존 `준비한다()`·`useCase`·`state` 를 쓴다):

```java
    @Test
    @DisplayName("조직 PATCH(증분)로 externalId 를 다른 조직의 값으로 바꾸면 409 이고 아무것도 쓰지 않는다(설계 2026-10-06 §4.1)")
    void PATCH_로_externalId_를_남의_값으로_바꾸면_409다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();
        useCase.createGroup(new DirectoryGroup("g-2", "DEV002", "백엔드팀", Set.of())).block();

        // when
        var 바꾸기 = useCase.changeGroup("g-2", GroupChange.delta().renamed("백엔드팀(개명)").reidentified("DEV001"));

        // then
        assertThatThrownBy(바꾸기::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("externalId");
        assertThat(state.groups.get("g-2").externalId()).isEqualTo("DEV002");
        assertThat(state.groups.get("g-2").displayName()).isEqualTo("백엔드팀");
    }

    @Test
    @DisplayName("조직 PATCH(증분)로 externalId 만 바꾸면 이름은 그대로다")
    void PATCH_로_externalId_만_바꾼다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();

        // when
        useCase.changeGroup("g-1", GroupChange.delta().reidentified("DEV009")).block();

        // then
        assertThat(state.groups.get("g-1").externalId()).isEqualTo("DEV009");
        assertThat(state.groups.get("g-1").displayName()).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("조직 PATCH 로 externalId 를 비우면, 다른 조직이 그 옛 값으로 만들어질 수 있다")
    void externalId_를_비우면_옛_값을_다른_조직이_쓴다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();
        useCase.changeGroup("g-1", GroupChange.delta().reidentified(null)).block();

        // when
        useCase.createGroup(new DirectoryGroup("g-2", "DEV001", "새 개발본부", Set.of())).block();

        // then
        assertThat(state.groups.get("g-1").externalId()).isNull();
        assertThat(state.groups.get("g-2").externalId()).isEqualTo("DEV001");
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSyncCreateUniquenessTest'`
Expected: 컴파일 실패(`reidentified` 없음).

- [ ] **Step 3: 구현**

`GroupChange` 의 `renamed` 아래에:

```java
    /**
     * {@code externalId} 를 바꾼다(null 이면 비운다). 유스케이스가 헤더의 값이 바뀌었을 때만 PUT 과 같은 중복 판정을 락 안에서 한다
     * (설계 2026-10-06 §4.1, RFC 7643 §3.1 readWrite).
     */
    public GroupChange reidentified(String newExternalId) {
        return new GroupChange(renames, displayName, true, newExternalId, base, ops);
    }
```

자바독 `@param reidentifies {@code externalId} 를 바꾸는가 — PUT 만 참이다` 를
`@param reidentifies {@code externalId} 를 바꾸는가 — PUT, 그리고 {@code externalId} 를 다루는 PATCH 가 참이다` 로 고친다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/model/GroupChange.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCreateUniquenessTest.java
git commit -F - <<'EOF'
feat: 조직 변경에 externalId 바꾸기(reidentified) — PATCH 도 PUT 과 같은 중복 판정(④-1 이월)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 2: connector-scim — RFC 속성 표 `ScimRfcAttributes`

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRfcAttributes.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimRfcAttributesTest.java`

**Interfaces:**
- Produces:
  - `final class ScimRfcAttributes` (패키지 전용)
  - `static Optional<String> userAttribute(String path)` — path 가 RFC 가 정의한 직원 속성(과 그 RFC 하위 속성)을 가리키면 그 속성의 정규 이름(예: `"phoneNumbers"`, `"manager"`)을, 아니면 empty 를 돌려준다.
  - `static final String OTHER = "other"`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package dev.starryeye.organization.scim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ScimRfcAttributesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "phoneNumbers[type eq \"work\"].value | phoneNumbers",
            "addresses[type eq \"work\"].streetAddress | addresses",
            "emails[type eq \"other\"] | emails",
            "emails[type eq \"work\"].display | emails",
            "title | title",
            "PHONENUMBERS | phoneNumbers",
            "urn:ietf:params:scim:schemas:core:2.0:User:title | title",
            "URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:USER:nickName | nickName",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager | manager",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager.value | manager",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department | department",
            "groups | groups",
            "password | password",
            "x509Certificates[value eq \"a\"].$ref | x509Certificates"
    })
    @DisplayName("RFC 7643 코어 User(§4.1)·enterprise(§4.3)가 정의한 속성과 하위 속성은 정규 이름으로 알아본다(설계 2026-10-06 §3.2)")
    void RFC_가_정의한_속성을_알아본다(String path, String 정규_이름) {
        // when, then
        assertThat(ScimRfcAttributes.userAttribute(path)).contains(정규_이름);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name.givenNmae",
            "phoneNumber",
            "jobTitle",
            "id",
            "meta",
            "title[type eq \"x\"]",
            "urn:ietf:params:scim:schemas:extension:custom:2.0:User:costCenter",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:userName",
            "department",
            "phoneNumbers[type eq \"work\"].nope",
            ""
    })
    @DisplayName("RFC 에 없는 이름·하위 속성, 커스텀 확장, URN 없는 enterprise 이름은 모른다 — 400 으로 남긴다")
    void RFC_밖은_모른다(String path) {
        // when, then
        assertThat(ScimRfcAttributes.userAttribute(path)).isEmpty();
    }

    @Test
    @DisplayName("표에 없는 이름의 메트릭 태그는 other 하나다")
    void 표_밖의_태그는_other() {
        // when, then
        assertThat(ScimRfcAttributes.OTHER).isEqualTo("other");
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimRfcAttributesTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```java
package dev.starryeye.organization.scim;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RFC 7643 이 정의한 직원 속성 — 코어 User(§4.1)와 enterprise 확장(§4.3), 그리고 그 하위 속성(설계 2026-10-06 §3.2).
 * 우리가 저장하지 않는 속성을 path 로 받았을 때 둘을 가른다. "표준이 정의했지만 다루지 않는 것" 은 받아서 버리고, "모르는 것" 은 400 이다.
 * RFC 7643 은 2015 년 이후 바뀌지 않았다.
 */
final class ScimRfcAttributes {

    /** 표에 없는 키(경로 없는 값)를 메트릭 태그로 묶는 이름 — 요청 문자열을 태그에 싣지 않는다. */
    static final String OTHER = "other";

    private static final String CORE_USER_URN = "urn:ietf:params:scim:schemas:core:2.0:user:";
    private static final String ENTERPRISE_USER_URN = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:user:";

    /** RFC 7644 §3.10 의 PATCH path — {@code 속성[필터].하위속성}. 필터 안은 보지 않는다. */
    private static final Pattern PATH = Pattern.compile(
            "^(?<attr>[A-Za-z][\\w$-]*)(?:\\[(?<filter>.*)])?(?:\\.(?<sub>[A-Za-z$][\\w$-]*))?$", Pattern.DOTALL);

    /** 정규 이름과 소문자 하위 속성. 하위 속성이 없으면 단일 속성이다. */
    private record Attribute(String name, Set<String> subAttributes) {
    }

    /** 복수 속성의 공통 하위 속성(RFC 7643 §2.4). */
    private static final Set<String> 복수_하위 = Set.of("value", "display", "type", "primary", "$ref");

    private static final Map<String, Attribute> CORE = 표(
            단일("userName"), 단일("displayName"), 단일("nickName"), 단일("profileUrl"), 단일("title"),
            단일("userType"), 단일("preferredLanguage"), 단일("locale"), 단일("timezone"), 단일("active"), 단일("password"),
            new Attribute("name", 소문자("formatted", "familyName", "givenName", "middleName",
                    "honorificPrefix", "honorificSuffix")),
            복수("emails"), 복수("phoneNumbers"), 복수("ims"), 복수("photos"), 복수("groups"),
            복수("entitlements"), 복수("roles"), 복수("x509Certificates"),
            new Attribute("addresses", 소문자("formatted", "streetAddress", "locality", "region", "postalCode",
                    "country", "type", "primary")));

    private static final Map<String, Attribute> ENTERPRISE = 표(
            단일("employeeNumber"), 단일("costCenter"), 단일("organization"), 단일("division"), 단일("department"),
            new Attribute("manager", 소문자("value", "$ref", "displayName")));

    private ScimRfcAttributes() {
    }

    /**
     * path 가 RFC 가 정의한 직원 속성을 가리키면 그 속성의 정규 이름을 돌려준다. 하위 속성이 있으면 그것도 RFC 가 정의한 것이어야 한다.
     * 코어 User URN 접두는 떼고 코어 표에서, enterprise URN 접두는 떼고 enterprise 표에서 찾는다.
     * URN 없는 enterprise 이름(예: {@code department})은 RFC 의 모양이 아니라 모른다(RFC 7644 §3.10).
     */
    static Optional<String> userAttribute(String path) {
        String rest = path.trim();
        String lower = rest.toLowerCase(Locale.ROOT);
        Map<String, Attribute> table = CORE;
        if (lower.startsWith(ENTERPRISE_USER_URN)) {
            table = ENTERPRISE;
            rest = rest.substring(ENTERPRISE_USER_URN.length());
        } else if (lower.startsWith(CORE_USER_URN)) {
            rest = rest.substring(CORE_USER_URN.length());
        }
        Matcher matcher = PATH.matcher(rest);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Attribute attribute = table.get(matcher.group("attr").toLowerCase(Locale.ROOT));
        if (attribute == null) {
            return Optional.empty();
        }
        // 필터와 하위 속성은 하위 속성을 가진 속성에만 뜻이 있다
        if ((matcher.group("filter") != null || matcher.group("sub") != null) && attribute.subAttributes().isEmpty()) {
            return Optional.empty();
        }
        String sub = matcher.group("sub");
        if (sub != null && !attribute.subAttributes().contains(sub.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(attribute.name());
    }

    private static Attribute 단일(String name) {
        return new Attribute(name, Set.of());
    }

    private static Attribute 복수(String name) {
        return new Attribute(name, 복수_하위);
    }

    private static Set<String> 소문자(String... names) {
        return Arrays.stream(names).map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, Attribute> 표(Attribute... attributes) {
        return Arrays.stream(attributes).collect(Collectors.toUnmodifiableMap(
                attribute -> attribute.name().toLowerCase(Locale.ROOT), Function.identity()));
    }
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimRfcAttributesTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRfcAttributes.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimRfcAttributesTest.java
git commit -F - <<'EOF'
feat: RFC 7643 직원 속성 표(코어 User·enterprise, 하위 속성) — 저장하지 않는 path 를 "표준이 정의한 것" 과 "모르는 것" 으로 가른다(점검 M5·M18)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 3: connector-scim — 직원 PATCH: 저장하지 않는 RFC 속성은 받아서 버리고, `active` 는 표준 신호만

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java` — 클래스 자바독, `applyToUser`, 직원 `applyOne`, `mergeUserAttributes`, `asBoolean`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java`
  - 바꾼다: `저장하지_않는_경로는_invalidPath`(~597), `확장_스키마_URN_path는_invalidPath`(~703)
  - 더한다: 아래 테스트들

**Interfaces:**
- Consumes: Task 2 의 `ScimRfcAttributes.userAttribute(String)`, `ScimRfcAttributes.OTHER`.
- Produces:
  - `public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch, Consumer<String> 버림)` — 받아서 버린 속성마다 `버림` 에 정규 이름(또는 `"other"`)을 넘긴다. 같은 이름이 여러 번 갈 수 있다(받는 쪽이 집합으로 모은다).
  - 기존 `applyToUser(DirectoryUser, ScimPatchOp)` 는 남기고 `이름 -> { }` 로 위임한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`저장하지_않는_경로는_invalidPath` 를 지우고 아래로 바꾼다:

```java
    @Test
    @DisplayName("RFC 가 정의했지만 저장하지 않는 속성은 path 로 와도 받아서 버린다 — 직원은 그대로다(설계 2026-10-06 §3.1, 점검 M5)")
    void 저장하지_않는_RFC_속성은_받아서_버린다() {
        // given
        var before = 이름있는_직원();
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(before, 패치(
                new ScimOperation("replace", "emails[type eq \"home\"].value", "a@x.com"),
                new ScimOperation("replace", "title", "과장"),
                new ScimOperation("replace", "phoneNumbers[type eq \"mobile\"].value", "010"),
                new ScimOperation("replace", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department", "개발")),
                버린것::add);

        // then
        assertThat(after).isEqualTo(before);
        assertThat(버린것).containsExactly("emails", "title", "phoneNumbers", "department");
    }
```

`확장_스키마_URN_path는_invalidPath` 를 지우고 아래 둘로 바꾼다:

```java
    @Test
    @DisplayName("enterprise 확장의 manager·employeeNumber path 는 받아서 버린다 — Entra 참조 실패로 세지지 않는다(점검 M18)")
    void enterprise_속성은_받아서_버린다() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치(
                new ScimOperation("add", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager", "boss-1"),
                new ScimOperation("replace", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber", "Aklq")),
                버린것::add);

        // then
        assertThat(after).isEqualTo(이름있는_직원());
        assertThat(버린것).containsExactly("manager", "employeeNumber");
    }

    @Test
    @DisplayName("RFC 에 없는 path 는 지금처럼 400 invalidPath 다 — 오타·커스텀 확장·URN 없는 enterprise 이름·공통 속성 id")
    void RFC_밖의_path는_invalidPath() {
        // when, then
        거절한다(패치("replace", "name.givenNmae", "철수"), "invalidPath");
        거절한다(패치("replace", "phoneNumber", "010"), "invalidPath");
        거절한다(패치("replace", "urn:ietf:params:scim:schemas:extension:custom:2.0:User:costCenter", "C1"), "invalidPath");
        거절한다(패치("replace", "department", "개발"), "invalidPath");
        거절한다(패치("replace", "id", "other-id"), "invalidPath");
    }
```

새 테스트 넷(Review Focus 1·2 포함):

```java
    @Test
    @DisplayName("점검 M5 의 Entra 요청 — 전화번호 path 연산과 경로 없는 active=false 가 한 요청에 오면 비활성화가 반영된다")
    void 전화번호_path_가_섞여도_비활성화된다() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치(
                new ScimOperation("replace", "phoneNumbers[type eq \"work\"].value", "010-1234-5678"),
                new ScimOperation("replace", null, Map.of("active", false))), 버린것::add);

        // then
        assertThat(after.active()).isFalse();
        assertThat(버린것).containsExactly("phoneNumbers");
    }

    @Test
    @DisplayName("한 요청에 섞인 연산 — 저장하는 path 는 모두 반영되고 저장하지 않는 것만 버린다")
    void 섞인_연산은_저장하는_것만_반영한다() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치(
                new ScimOperation("replace", "addresses[type eq \"work\"].streetAddress", "판교로 1"),
                new ScimOperation("replace", "displayName", "김철수(개명)"),
                new ScimOperation("replace", null, Map.of("active", false, "nickName", "철이"))), 버린것::add);

        // then
        assertThat(after.displayName()).isEqualTo("김철수(개명)");
        assertThat(after.active()).isFalse();
        assertThat(버린것).containsExactly("addresses", "nickName");
    }

    @Test
    @DisplayName("저장하는 work 이메일의 다른 하위 속성(.display)은 버리고 이메일은 그대로다")
    void work_이메일의_display_는_버린다() {
        // given
        var before = 이름있는_직원();
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(before,
                패치("replace", "emails[type eq \"work\"].display", "회사 메일"), 버린것::add);

        // then
        assertThat(after.email()).isEqualTo(before.email());
        assertThat(버린것).containsExactly("emails");
    }

    @Test
    @DisplayName("경로 없는 값의 모르는 키는 지금처럼 무시하되, 표에 없는 키의 이름은 other 로 알린다 — 요청 문자열을 그대로 넘기지 않는다")
    void 경로_없는_값의_표_밖_키는_other() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", null, Map.of(
                "urn:ietf:params:scim:schemas:extension:custom:2.0:User:costCenter", "C1")), 버린것::add);

        // then
        assertThat(after).isEqualTo(이름있는_직원());
        assertThat(버린것).containsExactly("other");
    }
```

`active` 값(S8):

```java
    @ParameterizedTest
    @ValueSource(strings = {"true", "True", "TRUE"})
    @DisplayName("active 문자열 \"true\" 는 대소문자 없이 참이다 — Entra 는 문자열로 보낸다")
    void active_문자열_참(String 값) {
        // when
        var after = ScimPatchApplier.applyToUser(직원(false), 패치("replace", "active", 값));

        // then
        assertThat(after.active()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "False"})
    @DisplayName("active 문자열 \"false\" 는 대소문자 없이 거짓이다")
    void active_문자열_거짓(String 값) {
        // when
        var after = ScimPatchApplier.applyToUser(직원(true), 패치("replace", "active", 값));

        // then
        assertThat(after.active()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", " true", ""})
    @DisplayName("active 의 그 밖의 문자열은 조용히 비활성화하지 않고 400 invalidValue 다(설계 2026-10-06 §5.2, 점검 S8)")
    void active_의_그_밖_문자열은_invalidValue(String 값) {
        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(직원(true), 패치("replace", "active", 값)))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("invalidValue");
                });
    }
```

(`@ParameterizedTest`·`@ValueSource` import 를 더한다. `이름있는_직원()`·`직원(boolean)`·`거절한다(…)`·`패치(…)` 는 이 파일에 이미 있다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimPatchApplierTest'`
Expected: 컴파일 실패(세 인자 `applyToUser` 없음), 그다음 단정 실패.

- [ ] **Step 3: 구현**

클래스 자바독의 둘째 문장부터 다음으로 바꾼다:

```java
 * <p>직원은 우리가 저장하는 속성 전부, 조직은 members·displayName·externalId 를 다룬다(S-3 설계 §7.2, 설계 2026-10-06 §4). 속성 이름은
 * 대소문자를 가리지 않는다. 저장하는 속성은 엄격하게 적용한다. RFC 7643 이 정의했지만 저장하지 않는 직원 속성은 path 로 와도 받아서
 * 버린다 — 저장하지도 돌려주지도 않으니 불일치가 보일 곳이 없다. 거절하면 오히려 같은 요청의 비활성화까지 막힌다(설계 2026-10-06 §3,
 * 점검 M5). RFC 에 없는 path 만 400 이다. 조직은 {@link GroupChange} 로 정리만 하고 적용은 유스케이스가 한다.
```

`applyToUser`:

```java
    /** 직원 PATCH. 받아서 버린 속성의 이름({@link ScimRfcAttributes} 의 정규 이름이나 {@code other})을 {@code 버림} 으로 넘긴다(설계 2026-10-06 §3.3). */
    public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch, Consumer<String> 버림) {
        DirectoryUser current = before;
        for (ScimOperation operation : operations(patch)) {
            current = applyOne(current, operation, 버림);
        }
        return current;
    }

    public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch) {
        return applyToUser(before, patch, 이름 -> { });
    }
```

직원 `applyOne`:

```java
    private static DirectoryUser applyOne(DirectoryUser user, ScimOperation operation, Consumer<String> 버림) {
        String op = requireKnownOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireTarget(op);
            requireReplaceOrAdd(op, operation.op());
            return mergeUserAttributes(op, user, asAttributeMap(operation.value()), 버림);
        }

        Optional<DirectoryUser> applied = applyPath(user, op, path.trim(), operation.value());
        if (applied.isPresent()) {
            return applied.get();
        }
        // 저장하지 않는 속성 — RFC 가 정의한 것이면 받아서 버리고, 모르는 것만 거절한다(설계 2026-10-06 §3.1)
        String 버린것 = ScimRfcAttributes.userAttribute(path)
                .orElseThrow(() -> ScimException.invalidPath("지원하지 않는 path 입니다: " + path));
        버림.accept(버린것);
        return user;
    }
```

`mergeUserAttributes`:

```java
    /**
     * 경로 없는 add/replace — 값 객체의 키마다 {@code (op, path=키, value=값)} 연산 하나로 보고
     * {@link #applyPath} 로 적용한다(F1). 모르는 키는 지금처럼 무시한다(설계 2026-10-06 §3.4). 그 이름은 표의 정규 이름이나 {@code other} 로 알린다 —
     * 요청 문자열을 메트릭 태그로 넘기지 않는다. Jackson 은 값 객체를 {@code LinkedHashMap} 으로 주므로 키 순서대로 누적 적용된다.
     */
    private static DirectoryUser mergeUserAttributes(String op, DirectoryUser user, Map<String, Object> attributes,
                                                     Consumer<String> 버림) {
        DirectoryUser merged = user;
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Optional<DirectoryUser> applied = applyPath(merged, op, entry.getKey(), entry.getValue());
            if (applied.isPresent()) {
                merged = applied.get();
            } else {
                버림.accept(ScimRfcAttributes.userAttribute(entry.getKey()).orElse(ScimRfcAttributes.OTHER));
            }
        }
        return merged;
    }
```

`asBoolean`:

```java
    /**
     * {@code active} 값 — JSON boolean, 또는 문자열 {@code "true"}/{@code "false"}(대소문자 무관, Entra 가 문자열로 보낸다)만 받는다.
     * 그 밖을 거짓으로 읽으면 조용히 비활성화하므로 400 이다(설계 2026-10-06 §5.2, 점검 S8).
     */
    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw ScimException.invalidValue("active 는 true 또는 false 여야 합니다: " + value);
    }
```

`java.util.function.Consumer` import 를 더한다. 같은 파일·다른 테스트 파일에 `"boolean 값이 아닙니다"` 나 그 `invalidSyntax` 를 단정하는 기존 테스트가 있으면 `invalidValue` 로 고친다.
같은 모듈에서 `title`·`phoneNumbers`·enterprise path 를 400 으로 단정하는 다른 테스트(예: `ScimUserHandlerTest`)가 있으면 이 규칙으로 고친다. 고친 곳은 보고서에 적는다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java
git commit -F - <<'EOF'
feat: 직원 PATCH — RFC 가 정의했지만 저장하지 않는 속성은 path 로 와도 받아서 버린다(비활성화가 막히지 않는다), active 는 boolean·"true"/"false" 만(점검 M5·M18·S8)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

(다른 테스트 파일을 고쳤으면 그 경로도 `git add` 한다.)

---

### Task 4: connector-scim — 조직 PATCH: `externalId` 와 코어 Group URN 접두

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java` — `typeless`, 조직 `applyOne`, `mergeGroupAttributes`, URN 떼기 도우미
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java` (더함)

**Interfaces:**
- Consumes: Task 1 의 `GroupChange.reidentified(String)`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
    @Test
    @DisplayName("조직 PATCH path externalId — add·replace 는 그 값으로, remove 는 비운다(설계 2026-10-06 §4.1)")
    void 조직_path_externalId() {
        // when
        var 바꿈 = ScimPatchApplier.toGroupChange(패치("replace", "externalId", "EXT-9"), USER_ONLY).block();
        var 비움 = ScimPatchApplier.toGroupChange(패치("remove", "externalId", null), USER_ONLY).block();

        // then
        assertThat(바꿈.reidentifies()).isTrue();
        assertThat(바꿈.externalId()).isEqualTo("EXT-9");
        assertThat(비움.reidentifies()).isTrue();
        assertThat(비움.externalId()).isNull();
        assertThat(적용한다(조직(MemberRef.user("kim")), 패치("replace", "externalId", "EXT-9"), USER_ONLY))
                .satisfies(after -> {
                    assertThat(after.externalId()).isEqualTo("EXT-9");
                    assertThat(after.members()).containsExactly(MemberRef.user("kim"));
                });
    }

    @Test
    @DisplayName("경로 없는 값의 externalId 키도 path 와 같은 규칙이다 — 조용히 무시하지 않는다(④-1 이월)")
    void 조직_경로_없는_값의_externalId() {
        // when
        var change = ScimPatchApplier.toGroupChange(
                패치("replace", null, Map.of("externalId", "EXT-9", "displayName", "플랫폼팀")), USER_ONLY).block();

        // then
        assertThat(change.reidentifies()).isTrue();
        assertThat(change.externalId()).isEqualTo("EXT-9");
        assertThat(change.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("Okta 가 경로 없는 값에 싣는 id 키는 지금처럼 무시한다 — externalId 를 건드리지 않는다")
    void 조직_경로_없는_값의_id_는_무시한다() {
        // when
        var change = ScimPatchApplier.toGroupChange(
                패치("replace", null, Map.of("id", "DEV002", "displayName", "플랫폼팀")), USER_ONLY).block();

        // then
        assertThat(change.reidentifies()).isFalse();
        assertThat(change.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("조직 PATCH externalId 의 모르는 op 는 400 invalidSyntax 다")
    void 조직_externalId_모르는_op() {
        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.toGroupChange(패치("move", "externalId", "EXT-9"), USER_ONLY).block())
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getScimType()).isEqualTo("invalidSyntax"));
    }

    @Test
    @DisplayName("조직 PATCH 도 코어 Group URN 접두를 대소문자 없이 뗀다 — path 와 경로 없는 값의 키(설계 2026-10-06 §4.2, 점검 S9)")
    void 조직_Group_URN_접두를_뗀다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var path형 = 적용한다(before,
                패치("replace", "urn:ietf:params:scim:schemas:core:2.0:Group:displayName", "플랫폼팀"), USER_ONLY);
        var 값형 = 적용한다(before,
                패치("replace", null, Map.of("URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:GROUP:displayName", "플랫폼팀")), USER_ONLY);

        // then
        assertThat(path형.displayName()).isEqualTo("플랫폼팀");
        assertThat(값형.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("경로 없는 값의 URN 접두 붙은 members 도 type 없는 멤버를 현재상태로 판정한다 — 모으는 쪽과 적용하는 쪽이 같은 이름을 본다")
    void URN_접두_members_의_type_없는_멤버를_판정한다() {
        // given
        MemberTypeResolver 하위조직이다 = ids -> Mono.just(
                ids.stream().collect(Collectors.toMap(id -> id, id -> MemberType.GROUP)));

        // when
        var after = 적용한다(조직(), 패치("add", null, Map.of(
                "urn:ietf:params:scim:schemas:core:2.0:Group:members", List.of(Map.of("value", "SUB1")))), 하위조직이다);

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("SUB1"));
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimPatchApplierTest'`
Expected: 단정 실패.
- path `externalId` 는 400 이다.
- 경로 없는 `externalId` 는 무시된다.
- URN 접두 path 는 400 이다.
- URN `members` 는 무시된다.

- [ ] **Step 3: 구현**

URN 떼기를 하나로 모은다(직원 `stripCoreUrn` 을 대체):

```java
    /** 코어 스키마 URN 접두(RFC 7643 §3.10) — path·경로 없는 값의 키 모두에서 대소문자 없이 뗀다(F1). 직원은 User, 조직은 Group(설계 2026-10-06 §4.2). */
    private static final String CORE_USER_URN = "urn:ietf:params:scim:schemas:core:2.0:user:";
    private static final String CORE_GROUP_URN = "urn:ietf:params:scim:schemas:core:2.0:group:";

    private static String stripUrn(String name, String urn) {
        if (name.length() > urn.length() && name.substring(0, urn.length()).equalsIgnoreCase(urn)) {
            return name.substring(urn.length());
        }
        return name;
    }

    /** 조직 경로 없는 값 — 키의 Group URN 접두를 뗀 사본. 키 순서는 지킨다. 값 객체가 아니면 null(모으는 쪽은 예외를 던지지 않는다). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> groupAttributesOrNull(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> 정리 = new LinkedHashMap<>();
        ((Map<String, Object>) map).forEach((key, v) -> 정리.put(stripUrn(key, CORE_GROUP_URN), v));
        return 정리;
    }
```

직원 쪽 `stripCoreUrn(path)` 호출은 `stripUrn(path, CORE_USER_URN)` 으로 바꾸고 옛 메서드는 지운다.

`typeless` — 경로 없는 값과 path 모두 URN 을 뗀 이름으로 본다:

```java
        if (path == null || path.isBlank()) {
            Map<String, Object> attributes = groupAttributesOrNull(operation.value());
            if (attributes != null) {
                members = attribute(attributes, "members");
            }
        } else if (stripUrn(path.trim(), CORE_GROUP_URN).equalsIgnoreCase("members")) {
            members = operation.value();
        }
```

조직 `applyOne`:

```java
    private static GroupChange applyOne(GroupChange change, ScimOperation operation, Map<String, MemberType> 종류) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireTarget(op);
            requireReplaceOrAdd(op, operation.op());
            Map<String, Object> attributes = groupAttributesOrNull(operation.value());
            if (attributes == null) {
                throw ScimException.invalidSyntax("값은 객체여야 합니다");
            }
            return mergeGroupAttributes(change, op, attributes, 종류);
        }

        String target = stripUrn(path.trim(), CORE_GROUP_URN);
        Matcher filter = MEMBER_FILTER.matcher(target);
        // … 이하 기존 분기에서 path.trim() 을 target 으로 바꾼다(members, displayName). 오류 메시지는 원래 path 를 싣는다 …

        if (target.equalsIgnoreCase("externalId")) {
            // RFC 7643 §3.1 readWrite — PUT 과 같은 중복 판정을 유스케이스가 락 안에서 한다(설계 2026-10-06 §4.1)
            return switch (op) {
                case "add", "replace" -> change.reidentified(asString(operation.value()));
                case "remove" -> change.reidentified(null);
                default -> throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + operation.op());
            };
        }

        throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
    }
```

`mergeGroupAttributes`:

```java
    private static GroupChange mergeGroupAttributes(GroupChange change, String op, Map<String, Object> attributes,
                                                    Map<String, MemberType> 종류) {
        GroupChange merged = has(attributes, "displayName")
                ? change.renamed(asString(attribute(attributes, "displayName")))
                : change;
        // 경로 없는 값의 externalId 도 path 와 같은 규칙이다 — 조용히 무시하지 않는다(설계 2026-10-06 §4.1, ④-1 이월)
        if (has(attributes, "externalId")) {
            merged = merged.reidentified(asString(attribute(attributes, "externalId")));
        }
        if (!has(attributes, "members")) {
            return merged;
        }
        Set<MemberRef> members = toMemberRefs(attribute(attributes, "members"), 종류);
        return op.equals("add") ? merged.adding(members) : merged.replacing(members);
    }
```

`java.util.LinkedHashMap` import 를 더한다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java
git commit -F - <<'EOF'
feat: 조직 PATCH 가 externalId 를 바꾼다(path·경로 없는 값, PUT 과 같은 409 판정 — ④-1 이월), 코어 Group URN 접두를 뗀다(점검 S9)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 5: 버린 속성을 보이게 — 관찰자, 직원 PATCH 핸들러, 메트릭

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/IgnoredAttributeObserver.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java` (생성자, `patch`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java` (`scimUserHandler` 빈)
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncMetrics.java` (관찰자 구현, 자바독 표)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java`(더함), `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncMetricsTest.java`(더함)

**Interfaces:**
- Consumes: Task 3 의 `ScimPatchApplier.applyToUser(DirectoryUser, ScimPatchOp, Consumer<String>)`.
- Produces:
  - `public interface IgnoredAttributeObserver { IgnoredAttributeObserver NOOP; void ignored(Set<String> names); }`
  - `ScimUserHandler(DirectoryStateRepository, IncrementalSyncUseCase, IgnoredAttributeObserver)`. 기존 두 인자 생성자는 `NOOP` 으로 위임한다.
  - 메트릭 `scim.patch.ignored`(Counter, 태그 `attribute`).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimUserHandlerTest` 에 더한다. 기존 `setUp()` 이 만든 것과 같은 조립에, 기록하는 관찰자를 단 클라이언트를 테스트 안에서 만든다. 아래 `useCase` 는 `setUp()` 의 지역 변수이니 필드로 올리거나 같은 방식으로 새로 만든다:

```java
    @Test
    @DisplayName("직원 PATCH 가 받아서 버린 속성은 이름만 관찰자로 간다 — 비활성화는 반영된다(설계 2026-10-06 §3.3, 점검 M5)")
    void 버린_속성을_관찰자에게_알린다() {
        // given
        List<Set<String>> 알림 = new ArrayList<>();
        var 관찰하는_클라이언트 = 클라이언트(알림::add);
        String id = 직원을_만든다(관찰하는_클라이언트, "kim");
        String body = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[
                   {"op":"replace","path":"phoneNumbers[type eq \\"work\\"].value","value":"010-1234-5678"},
                   {"op":"replace","value":{"active":false,
                     "urn:ietf:params:scim:schemas:extension:custom:2.0:User:costCenter":"C1"}}]}
                """;

        // when
        관찰하는_클라이언트.patch().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        // then
        assertThat(state.users.get(id).active()).isFalse();
        assertThat(알림).containsExactly(Set.of("phoneNumbers", "other"));
    }

    @Test
    @DisplayName("버린 속성이 없으면 관찰자를 부르지 않는다")
    void 버린_것이_없으면_부르지_않는다() {
        // given
        List<Set<String>> 알림 = new ArrayList<>();
        var 관찰하는_클라이언트 = 클라이언트(알림::add);
        String id = 직원을_만든다(관찰하는_클라이언트, "lee");

        // when
        관찰하는_클라이언트.patch().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":false}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        // then
        assertThat(알림).isEmpty();
    }
```

도우미 둘(같은 파일, 기존 조립과 같은 모양):

```java
    private WebTestClient 클라이언트(IgnoredAttributeObserver 관찰자) {
        var useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        return WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase, 관찰자),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(state, query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();
    }

    private static String 직원을_만든다(WebTestClient 클라이언트, String userName) {
        return (String) com.jayway.jsonpath.JsonPath.read(new String(클라이언트.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"%s","active":true}
                        """.formatted(userName))
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody(), java.nio.charset.StandardCharsets.UTF_8), "$.id");
    }
```

`ScimSyncMetricsTest` 에 더한다:

```java
    @Test
    @DisplayName("받아서 버린 속성은 이름 태그를 달고 하나씩 오른다(설계 2026-10-06 §3.3)")
    void 버린_속성을_센다() {
        // when
        metrics.ignored(Set.of("phoneNumbers", "other"));
        metrics.ignored(Set.of("phoneNumbers"));

        // then
        assertThat(registry.counter("scim.patch.ignored", "attribute", "phoneNumbers").count()).isEqualTo(2);
        assertThat(registry.counter("scim.patch.ignored", "attribute", "other").count()).isEqualTo(1);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimUserHandlerTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`IgnoredAttributeObserver`:

```java
package dev.starryeye.organization.scim;

import java.util.Set;

/**
 * 직원 PATCH 가 받아서 버린 속성을 알린다(설계 2026-10-06 §3.3). 이름은 {@link ScimRfcAttributes} 의 정규 이름이거나 {@code other} 다 —
 * 요청 문자열이 아니라서 메트릭 태그로 써도 수가 늘 유한하다.
 */
@FunctionalInterface
public interface IgnoredAttributeObserver {

    IgnoredAttributeObserver NOOP = names -> { };

    /** 한 요청에서 버린 속성 이름들. 버린 것이 없으면 부르지 않는다. */
    void ignored(Set<String> names);
}
```

`ScimUserHandler` — `@RequiredArgsConstructor` 를 지우고 생성자 둘과 `@Slf4j` 를 둔다:

```java
@Slf4j
public class ScimUserHandler {

    private final DirectoryStateRepository state;
    private final IncrementalSyncUseCase sync;
    private final IgnoredAttributeObserver ignoredAttributes;

    public ScimUserHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync) {
        this(state, sync, IgnoredAttributeObserver.NOOP);
    }

    public ScimUserHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync,
                           IgnoredAttributeObserver ignoredAttributes) {
        this.state = state;
        this.sync = sync;
        this.ignoredAttributes = ignoredAttributes;
    }
```

`patch`:

```java
    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimPatchOp.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(patch -> {
                    // 락 안의 계산이 다시 돌 수 있어 집합으로 모은다
                    Set<String> 버린것 = ConcurrentHashMap.newKeySet();
                    return sync.changeUser(id, before -> ScimPatchApplier.applyToUser(before, patch, 버린것::add))
                            .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                            .doOnNext(result -> 버린것을_알린다(id, 버린것));
                })
                .flatMap(result -> respond(HttpStatus.OK, id, result, projection)));
    }

    /** 받아서 버린 속성 — 이름만 남긴다. 값(전화번호·주소)은 개인정보라 로그에 싣지 않는다(설계 2026-10-06 §3.3). WARN 이 아닌 까닭: IdP 가 갱신마다 다시 보낸다. */
    private void 버린것을_알린다(String id, Set<String> 버린것) {
        if (버린것.isEmpty()) {
            return;
        }
        Set<String> 이름들 = new TreeSet<>(버린것);
        log.debug("SCIM 직원 PATCH 가 저장하지 않는 속성을 받아서 버렸다: id={}, 속성={}", id, 이름들);
        ignoredAttributes.ignored(Set.copyOf(이름들));
    }
```

(import: `lombok.extern.slf4j.Slf4j`, `java.util.Set`, `java.util.TreeSet`, `java.util.concurrent.ConcurrentHashMap`. `lombok.RequiredArgsConstructor` import 는 지운다.)

`ScimConfig.scimUserHandler`:

```java
    /** 받아서 버린 속성을 알릴 관찰자가 있으면 단다 — app-scim 의 메트릭(설계 2026-10-06 §3.3). 없으면 아무 일도 하지 않는다. */
    @Bean
    public ScimUserHandler scimUserHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync,
                                           ObjectProvider<IgnoredAttributeObserver> ignoredAttributes) {
        return new ScimUserHandler(state, sync, ignoredAttributes.getIfAvailable(() -> IgnoredAttributeObserver.NOOP));
    }
```

`ScimSyncMetrics` — `implements DriftObserver, LockObserver, IgnoredAttributeObserver`. 자바독 첫 줄 "설계 §7 의 지표 넷을" 을 "설계 §7 의 지표 넷과 받아서 버린 속성(설계 2026-10-06 §3.3)을" 로 고친다. 표에 줄을 하나 더한다: `<tr><td>{@code scim.patch.ignored}</td><td>Counter</td><td>{@code attribute} = RFC 정규 이름 / other</td></tr>`.

```java
    /** 태그 값은 {@code ScimRfcAttributes} 의 정규 이름이나 other 뿐이다 — 요청 문자열이 태그가 되지 않는다. */
    @Override
    public void ignored(Set<String> names) {
        names.forEach(name -> registry.counter("scim.patch.ignored", "attribute", name).increment());
    }
```

(import: `dev.starryeye.organization.scim.IgnoredAttributeObserver`, `java.util.Set`.)

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`, 그다음 `./gradlew :app-scim:test --tests '*ScimSyncMetricsTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/IgnoredAttributeObserver.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncMetrics.java app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncMetricsTest.java
git commit -F - <<'EOF'
feat: 직원 PATCH 가 받아서 버린 속성을 보이게 — 관찰자 + 메트릭 scim.patch.ignored{attribute}(태그는 RFC 정규 이름·other 만), DEBUG 한 줄에 이름만(점검 M5)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 6: 형식 — 속성 이름 대소문자(S6), `Location`(S1), POST 의 `active` 문자열(S8)

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimUser.java`, `ScimGroup.java`, `ScimName.java`, `ScimEmail.java`, `ScimMember.java`, `ScimPatchOp.java`, `ScimOperation.java`, `ScimSearchRequest.java` (애노테이션 한 줄씩)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java` (위치 도우미)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java`, `ScimGroupHandler.java` (`respond` 에 `Location`)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java`, `ScimGroupHandlerTest.java` (더함)

**Interfaces:**
- Produces: `ScimMapper.userLocation(String id)` → `"/scim/v2/Users/" + id`, `ScimMapper.groupLocation(String id)` → `"/scim/v2/Groups/" + id`. 둘 다 `public static`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimUserHandlerTest`:

```java
    @Test
    @DisplayName("직원 POST 201 에 Location 헤더가 있고 본문 meta.location 과 같다(RFC 7644 §3.3, 점검 S1)")
    void 직원_POST_는_Location_을_단다() {
        // when
        var result = client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"kim","active":true}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();

        // then
        String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        String id = JsonPath.read(body, "$.id");
        assertThat(result.getResponseHeaders().getLocation()).hasToString("/scim/v2/Users/" + id);
        assertThat((String) JsonPath.read(body, "$.meta.location")).isEqualTo("/scim/v2/Users/" + id);
    }

    @Test
    @DisplayName("직원 PUT 본문의 속성 이름은 대소문자를 가리지 않는다 — \"Active\":false 가 비활성이다(RFC 7643 §2.1, 점검 S6)")
    void PUT_속성_이름_대소문자() {
        // given
        String id = 직원을_만든다(client, "kim");

        // when
        client.put().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"Schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"UserName":"kim","Active":false}
                        """)
                .exchange()
                .expectStatus().isOk();

        // then
        assertThat(state.users.get(id).active()).isFalse();
    }

    @Test
    @DisplayName(".search 본문의 \"Filter\" 도 필터다 — 필터 없는 첫 페이지로 새지 않는다(점검 S6)")
    void search_의_Filter_대소문자() {
        // given
        직원을_만든다(client, "kim");
        직원을_만든다(client, "lee");

        // when, then
        client.post().uri("/scim/v2/Users/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"],"Filter":"userName eq \\"kim\\""}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(1);
    }

    @Test
    @DisplayName("PATCH 의 \"operations\"(소문자)도 받는다 — RFC 가 정한 \"Operations\" 와 같다")
    void PATCH_Operations_대소문자() {
        // given
        String id = 직원을_만든다(client, "kim");

        // when
        client.patch().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "operations":[{"OP":"replace","PATH":"active","VALUE":false}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        // then
        assertThat(state.users.get(id).active()).isFalse();
    }

    @Test
    @DisplayName("POST 본문의 active 가 \"yes\" 면 400 이다 — boolean 이 아닌 문자열을 참·거짓으로 읽지 않는다(점검 S8)")
    void POST_active_yes_는_400() {
        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"kim","active":"yes"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
        assertThat(state.users).isEmpty();
    }
```

(`JsonPath` 는 `com.jayway.jsonpath.JsonPath` 다. Task 5 의 `직원을_만든다` 도우미를 쓴다.)

`ScimGroupHandlerTest`:

```java
    @Test
    @DisplayName("조직 POST 201 에 Location 헤더가 있고 본문 meta.location 과 같다(점검 S1)")
    void 조직_POST_는_Location_을_단다() {
        // when
        var result = client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"개발본부"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();

        // then
        String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        String id = JsonPath.read(body, "$.id");
        assertThat(result.getResponseHeaders().getLocation()).hasToString("/scim/v2/Groups/" + id);
        assertThat((String) JsonPath.read(body, "$.meta.location")).isEqualTo("/scim/v2/Groups/" + id);
    }

    @Test
    @DisplayName("조직 PUT 의 \"Members\" 도 멤버다 — 대소문자가 달라 멤버 전원이 지워지지 않는다(RFC 7643 §2.1, 점검 S6)")
    void 조직_PUT_Members_대소문자() {
        // given — 직원 하나와 그 직원을 멤버로 둔 조직
        String userId = 만든다("/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"kim","active":true}
                """);
        String groupId = 만든다("/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"개발본부",
                 "members":[{"value":"%s","type":"User"}]}
                """.formatted(userId));

        // when
        client.put().uri("/scim/v2/Groups/" + groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"DisplayName":"개발본부",
                         "Members":[{"Value":"%s","Type":"User"}]}
                        """.formatted(userId))
                .exchange()
                .expectStatus().isOk();

        // then
        assertThat(state.groups.get(groupId).members()).containsExactly(MemberRef.user(userId));
    }
```

`ScimGroupHandlerTest` 에 도우미를 하나 더한다(같은 일을 하는 도우미가 이미 있으면 그것을 쓴다):

```java
    /** POST 하고 받은 id. */
    private String 만든다(String uri, String body) {
        return JsonPath.read(new String(client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8), "$.id");
    }
```

고치기 전에는 `"Members"` 를 몰라 멤버 목록이 비고, 마지막 단정이 실패한다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimUserHandlerTest' --tests '*ScimGroupHandlerTest'`
Expected:
- `Location` 이 없다(null).
- 대소문자가 다른 속성을 무시해 단정이 실패한다.
- `active:"yes"` 는 Jackson 이 이미 거절하면 처음부터 통과할 수 있다(확인용 — 보고서에 적는다).

- [ ] **Step 3: 구현**

DTO 여덟 — 각 record 의 애노테이션 줄에 더한다(import `com.fasterxml.jackson.annotation.JsonFormat`):

```java
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
```

`ScimUser` 의 자바독(없으면 한 줄 새로)에 "속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1, 설계 2026-10-06 §5.1) — 클래스 단위로 걸어 관리 API 의 JSON 은 그대로다" 를 적는다.

`ScimMapper`:

```java
    /** 리소스 위치 — 본문 {@code meta.location} 과 POST 201 의 {@code Location} 이 같은 값을 쓴다(RFC 7644 §3.3). 아이디는 서버 발급 UUID 라 인코딩할 글자가 없다(④-1). */
    public static String userLocation(String id) {
        return "/scim/v2/Users/" + id;
    }

    public static String groupLocation(String id) {
        return "/scim/v2/Groups/" + id;
    }
```

세 `new ScimMeta(…, "/scim/v2/…/" + …id())` 를 `userLocation(user.id())`, `groupLocation(group.id())`, `groupLocation(header.id())` 로 바꾼다.

`ScimUserHandler.respond` 의 응답 만들기:

```java
                .flatMap(saved -> {
                    ServerResponse.BodyBuilder builder = ServerResponse.status(status).contentType(SCIM_JSON);
                    // 생성은 Location 을 단다 — RFC 7644 §3.3 SHALL(설계 2026-10-06 §5.3)
                    if (status == HttpStatus.CREATED) {
                        builder.location(URI.create(ScimMapper.userLocation(id)));
                    }
                    return builder.bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(saved))));
                });
```

`ScimGroupHandler.respond` 도 같은 모양이다(`groupLocation(id)`, 본문은 지금처럼 `ScimJson.tree(scim)`). import `java.net.URI`.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`, 그다음 `./gradlew :app-scim:compileTestJava`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/dto connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java
git commit -F - <<'EOF'
feat: SCIM 본문 속성 이름은 대소문자를 가리지 않는다(점검 S6), POST 201 에 Location(meta.location 과 같은 값, 점검 S1)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 7: app-scim 끝에서 끝

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRequestInterpretationEndToEndTest.java`

**Interfaces:**
- Consumes: Task 1~6 전부. 메트릭 `scim.patch.ignored`(태그 `attribute`).

- [ ] **Step 1: 테스트를 쓴다**

`ScimErrorSignalsEndToEndTest` 의 클래스 모양을 그대로 따른다:
- `@Testcontainers`, `@ActiveProfiles("test")`, `@SpringBootTest(RANDOM_PORT)`
- `Containers.openFga()` / `Containers.dynamoDb()`
- `@DynamicPropertySource`, `@Autowired WebTestClient client`

`@Autowired MeterRegistry registry` 를 더한다.

```java
    @Test
    @DisplayName("점검 M5 의 Entra 요청 — 전화번호 path 연산과 active=false 가 한 요청에 와도 비활성화되고, 버린 속성이 메트릭에 잡힌다")
    void 전화번호가_섞인_비활성화() {
        // given
        String id = 만든다("/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"m5-kim","active":true}""");
        double 전 = registry.counter("scim.patch.ignored", "attribute", "phoneNumbers").count();

        // when
        client.patch().uri("/scim/v2/Users/" + id).contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[
                   {"op":"replace","path":"phoneNumbers[type eq \\"work\\"].value","value":"010-1234-5678"},
                   {"op":"replace","value":{"active":false}}]}""")
                .exchange()
                .expectStatus().isOk();

        // then
        client.get().uri("/scim/v2/Users/" + id).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false);
        assertThat(registry.counter("scim.patch.ignored", "attribute", "phoneNumbers").count()).isEqualTo(전 + 1);
    }

    @Test
    @DisplayName("조직 PATCH 로 다른 조직의 externalId 를 가져오면 409 이고 그대로다, 새 값이면 바뀐다(④-1 이월)")
    void 조직_PATCH_externalId() {
        // given
        만든다("/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"가","externalId":"e2e-EXT-A"}""");
        String b = 만든다("/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"나","externalId":"e2e-EXT-B"}""");

        // when, then — 겹치면 409
        client.patch().uri("/scim/v2/Groups/" + b).contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","path":"externalId","value":"e2e-EXT-A"}]}""")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");
        client.get().uri("/scim/v2/Groups/" + b).exchange()
                .expectBody().jsonPath("$.externalId").isEqualTo("e2e-EXT-B");

        // when, then — 경로 없는 값으로 새 값
        client.patch().uri("/scim/v2/Groups/" + b).contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","value":{"externalId":"e2e-EXT-C"}}]}""")
                .exchange()
                .expectStatus().isNoContent();
        client.get().uri("/scim/v2/Groups/" + b).exchange()
                .expectBody().jsonPath("$.externalId").isEqualTo("e2e-EXT-C");
    }

    @Test
    @DisplayName("POST 201 의 Location 이 본문 meta.location 과 같다(점검 S1)")
    void POST_의_Location() {
        // when
        var result = client.post().uri("/scim/v2/Users").contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"s1-kim"}""")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();

        // then
        String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        assertThat(result.getResponseHeaders().getLocation())
                .hasToString((String) JsonPath.read(body, "$.meta.location"));
    }

    /** POST 하고 받은 id. */
    private String 만든다(String uri, String body) {
        return JsonPath.read(new String(client.post().uri(uri).contentType(SCIM_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8), "$.id");
    }
```

같은 컨텍스트를 다른 클래스와 나누지 않는다(클래스마다 컨테이너). 그래도 메트릭은 전/후 차이로 본다. 아이디·`userName`·`externalId` 에 `m5-`, `e2e-`, `s1-` 접두를 달아 겹치지 않게 한다.

- [ ] **Step 2: 통과를 본다**

Run: `./gradlew :app-scim:test --tests '*ScimRequestInterpretationEndToEndTest'`
Expected: PASS.

확인을 위해 한 번 깨 본다.
1. `ScimPatchApplier` 의 직원 `applyOne` 에서 버리는 갈래를 잠깐 `throw ScimException.invalidPath(…)` 로 바꾼다.
2. 첫 테스트가 400 으로 실패하는 것을 본다.
3. 되돌린다.
4. `git diff connector-scim/` 가 비었는지 확인한다.

이 과정을 보고서에 적는다.

- [ ] **Step 3: 커밋**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRequestInterpretationEndToEndTest.java
git commit -F - <<'EOF'
test: app-scim e2e — Entra M5 요청(전화번호 path + 비활성화)·버린 속성 메트릭, 조직 PATCH externalId 409·변경, POST Location

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 8: README, 점검 문서, 스펙 보정

**Files:**
- Modify: `README.md`(SCIM 절, 메트릭 표), `docs/superpowers/specs/2026-09-28-full-audit.md`, `docs/superpowers/specs/2026-10-06-scim-request-interpretation-design.md`

- [ ] **Step 1: README**

1. 조직 `externalId` 문단(~580)의 "(조직 PATCH 는 `externalId` 를 바꿀 수 없다 — 경로로 지정하면 400 `invalidPath`)" 를 다음으로 바꾼다: "PATCH 도 같다 — path `externalId` 와 경로 없는 값의 `externalId` 키 모두. remove 는 비운다".
2. 경로 없는 값 문단(~585-590)과 저장 속성 문단(~592-597)을 하나로 다시 쓴다:
   - **저장하는 직원 속성**: `userName`, `displayName`, `externalId`, `active`, `name`(여섯 칸), `type: "work"` 이메일 하나.
   - **RFC 7643 이 정의했지만 저장하지 않는 속성**은 path 로 와도, 경로 없는 값으로 와도 받아서 버린다(204·200). 코어 User 의 `title`·`phoneNumbers`·`addresses`·`nickName`·work 가 아닌 이메일 등, enterprise 의 `department`·`employeeNumber`·`manager` 등이다. 그래서 IdP 매핑에 남아 있어도 같은 요청의 비활성화를 막지 않는다. `manager` 를 매핑에서 빼라는 안내는 없앤다(점검 M18).
   - **RFC 에 없는 path 는 400 `invalidPath` 다** — 오타(`name.givenNmae`), 커스텀 확장(`urn:…:extension:<이름>:2.0:User:…`), URN 없는 enterprise 이름. 커스텀 확장을 path 로 보내는 테넌트는 그 요청 전체가 실패하므로, Entra 는 `aadOptscim062020` 을 켜거나 커스텀 확장 매핑을 뺀다.
   - 버린 속성은 `scim_patch_ignored_total{attribute}` 로 본다(태그는 RFC 이름이나 `other`). 로그는 DEBUG 한 줄이고 값은 남기지 않는다.
3. 조직 PATCH 설명에 코어 Group URN 접두(`urn:ietf:params:scim:schemas:core:2.0:Group:`)를 받는다는 것을 적는다.
4. 요청 형식 한 문단:
   - 본문 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1).
   - PATCH `active` 는 boolean 이나 `"true"`/`"false"`(대소문자 무관)만 받고, 그 밖은 400 `invalidValue` 다.
   - POST 201 에 `Location`(본문 `meta.location` 과 같은 상대 경로)을 단다.
5. **거절하는 조회 모양(점검 S12)**:
   - 목록: `manager` 필터, `emails[type eq "work"]` 필터(Entra 이메일 매칭), Ping `co`, JumpCloud 이메일 재연결 — 모두 400.
   - 안내: 매칭 속성은 `userName` 이나 `externalId` 를 쓴다.
6. **조직 POST 재시도(점검 S5 앞부분)**:
   - `externalId` 없는 조직 POST 를 응답을 잃고 재시도하면 같은 이름의 조직이 생길 수 있다.
   - 멤버 없는 쪽이 재시도 찌꺼기다. 관리 API `GET /admin/organizations?displayName=…` 로 찾는다.
   - IdP 가 조직 `externalId` 를 보낼 수 있으면 매핑한다. 그러면 409 로 막힌다.
7. 메트릭 표(~752 근처)에 줄을 더한다: `scim.patch.ignored` | Counter | 받아서 버린 RFC 속성(태그 `attribute`). 꾸준히 오르는 이름이 있으면 IdP 매핑에서 빼도 된다는 신호다.

- [ ] **Step 2: 점검 문서**

- M5·M18(요약 표 행)과 S1·S6·S8·S9(부록 표 행) 끝에 `**→ 해결(2026-10-06, 슬라이드 ⑤-2)**` 를 단다. ⑤-1 표시와 같은 모양이다.
- S5 행의 "**앞쪽(…)은 남는다**" 뒤와 S12 행 끝에 `**→ 문서로 정리(2026-10-06, 슬라이드 ⑤-2)**` 를 단다.

- [ ] **Step 3: 스펙 보정**

컨트롤러가 넘기는 "구현 중 정한 것" 목록이 있으면 해당 절 끝에 한 줄씩 적는다. §9 는 건드리지 않는다(컨트롤러가 채운다). 스펙 문장이 코드와 다르면 코드에 맞춘다.

- [ ] **Step 4: 커밋**

```bash
git add README.md docs/superpowers/specs/2026-09-28-full-audit.md docs/superpowers/specs/2026-10-06-scim-request-interpretation-design.md
git commit -F - <<'EOF'
docs: ⑤-2 README — 저장하는 속성·받아서 버리는 RFC 속성·거절하는 조회 모양·조직 POST 재시도, 점검 M5·M18·S1·S6·S8·S9 해결·S5 앞부분·S12 문서화 표시, 설계 보정

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test`, 그다음 `./gradlew cleanScaleTest scaleTest` 를 한 번에 하나씩 돌린다.
- 스펙 §9 에 결과(테스트 수·시간)를 적고 커밋한다.

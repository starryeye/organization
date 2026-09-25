# 직원 이름 칸과 직원 PATCH (S-3) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 직원의 RFC `name` 여섯 칸을 SCIM·LDAP·저장소·admin 에서 담아 돌려주고, 직원 PATCH 가 우리가 저장하는 속성 전부(이름·이메일·externalId 포함)를 받게 해 Entra 의 이름+이메일 PATCH 가 성공하게 한다.

**Architecture:** core 에 값 객체 `PersonName` 을 두고 `DirectoryUser` 에 칸 하나(`name`)를 더한다(6인자 생성자는 "이름 없음" 으로 남김, 복사는 Lombok `@With`). 저장소는 평평한 문자열 속성 여섯, LDAP 은 표준 속성 넷을 읽고, admin 상세에 이름을 싣는다. SCIM 매퍼는 보낸 그대로 저장·응답하고, `ScimPatchApplier` 의 직원 경로를 표준대로 넓힌다.

**Tech Stack:** Java 17, Lombok(`@With` on records), Spring WebFlux, AWS SDK v2 DynamoDB, Spring LDAP, JUnit 5, AssertJ, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-09-26-scim-name-parts-design.md`](../specs/2026-09-26-scim-name-parts-design.md)

## Global Constraints

- 테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석. 기존 테스트의 형태를 그대로 따른다. Lombok 을 쓴다.
- **이름 여섯 칸(스펙 §3):** `formatted`, `familyName`, `givenName`, `middleName`, `honorificPrefix`, `honorificSuffix`. 빈 문자열은 "없음"(null). 여섯 칸이 모두 없으면 `PersonName.EMPTY` 와 같다.
- **`DirectoryUser` 를 필드 하나 바꿔 복사할 때는 반드시 `with…` 를 쓴다** — 6인자 생성자로 복사하면 이름이 조용히 사라진다. 6인자 생성자는 "처음부터 이름이 없는 직원" 을 만들 때만 쓴다.
- **저장소 속성 이름(스펙 §4):** `givenName`, `familyName`, `middleName`, `honorificPrefix`, `honorificSuffix`, `nameFormatted`. 값이 없으면 속성을 두지 않는다.
- **LDAP 매핑(스펙 §5):** `givenName`→`givenName`, `sn`→`familyName`, `generationQualifier`→`honorificSuffix`, `middleName`→`middleName`. 속성 이름은 설정으로 빼지 않는다.
- **PATCH(스펙 §7.2):** op 와 경로의 속성 이름은 대소문자를 가리지 않는다(직원·조직 모두). `userName` remove → 400 `mutability`. `emails[type eq "work"]…` replace 인데 이메일이 없으면 400 `noTarget`. 값 경로 필터는 `type eq "work"` 만. 그 밖의 경로는 400 `invalidPath`.
- **SCIM 응답(스펙 §7.1):** `name` 은 저장된 값 그대로, 모두 비었으면 `name` 을 넣지 않는다.
- **Gradle 규칙:** 서브에이전트는 자기 과제의 모듈 테스트만 포그라운드로 돌린다. 루트 `test`·`build`·`check`·`scaleTest` 전체는 돌리지 않는다. Gradle 을 둘 이상 동시에 돌리지 않는다.
- 커밋마다 푸시한다(`git push`). 커밋 트레일러: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## 스펙과 다르게 정한 것 (계획 작성 시 판정)

- **admin 직원 상세 JSON 의 `name` 은 여섯 칸을 모두 내보내고 없는 칸은 `null` 이다.** 스펙 §6 은 "값이 있는 칸만" 이라 적었지만, `PersonName` 은 Jackson 이 없는 core 모듈에 있고 admin 응답은 지금도 없는 값을 `null` 로 내보낸다(예: Check 실패 칸). 기존 admin 관례를 따른다.
- **`DirectoryUser` 에 Lombok `@With` 를 단다.** 스펙은 "6인자 생성자를 남긴다" 만 적었는데, 운영 코드에 필드 하나만 바꾼 복사가 세 곳(`IncrementalSyncUseCase` 둘, `ScimUserHandler.replace`) 있어 6인자로 복사하면 이름이 사라진다. 복사는 `with…` 로 바꾼다.

## Review Focus

1. **필드 하나만 바꾼 복사에서 이름이 사라짐** — 부분 실패 복구(`reconcileUser`)·PUT 의 id 고정이 이름을 지키는지. Task 1 과 Task 4 가 테스트한다.
2. **Entra 의 한 요청 이름+이메일 PATCH** — 둘 다 반영되고 200. Task 5 가 문서 예시를 글자 그대로 테스트한다.
3. **이름 없는 직원(LDAP 의 대부분, SCIM 에서 `name` 없이 생성)** — 응답에 `name` 이 아예 없고, 저장소에 이름 속성이 없고, 같은 값을 다시 저장하면 쓰지 않는다. Task 2·4 가 테스트한다.
4. **대소문자가 다른 경로·키**(`Name.GivenName`, `EMAILS[TYPE EQ "Work"].value`, 경로 없는 값 객체의 `DisplayName`, 조직의 `Members`) — 받아야 한다. Task 5 가 테스트한다.
5. **LDAP 픽스처의 `sn`** — 기존 픽스처는 inetOrgPerson 필수 속성으로 `sn` 을 갖고 있어 이제 `familyName` 이 채워진다. 직원을 통째로 비교하는 테스트가 있으면 기대값이 바뀌어야 한다(규모 검증기 `SyncVerifier` 는 필드별로 비교해 이름을 보지 않는다). Task 3 이 모듈 테스트로 확인한다.

## File Structure

| 파일 | 과제 | 책임 |
|---|---|---|
| `core/src/main/java/dev/starryeye/organization/core/model/PersonName.java` (신규) | 1 | 이름 여섯 칸 값 객체 |
| `core/src/main/java/dev/starryeye/organization/core/model/DirectoryUser.java` | 1 | `name` 칸, 6인자 생성자, `@With` |
| `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` | 1 | 복사 두 곳을 `with…` 로 |
| `core/src/main/java/dev/starryeye/organization/core/query/EmployeeDetail.java`, `core/.../usecase/AdminQueryUseCase.java` | 1 | 상세에 `name` |
| `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` | 2 | 이름 속성 여섯 쓰기·읽기 |
| `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapPersonName.java` (신규) | 3 | LDAP 표준 속성 → `PersonName` |
| `connector-ldap/.../strategy/GroupOfNamesStrategy.java`, `DitStrategy.java` | 3 | 직원에 이름 |
| `connector-scim/.../scim/dto/ScimName.java`, `ScimMapper.java`, `ScimResourceType.java`, `ScimUserHandler.java` | 4 | 생성·응답·속성 목록·PUT 복사 |
| `connector-scim/.../scim/ScimPatchApplier.java`, `ScimException.java` | 5 | 직원 PATCH 경로, 대소문자 무시, `mutability`·`noTarget` |
| `app-scim/src/test/.../ScimNameEndToEndTest.java` (신규), `README.md` | 6 | E2E, 안내 |

---

### Task 1: 도메인 — `PersonName` 과 `DirectoryUser.name`

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/PersonName.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/DirectoryUser.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (178·678줄 근처의 복사 두 곳)
- Modify: `core/src/main/java/dev/starryeye/organization/core/query/EmployeeDetail.java`, `core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java` (`toDetail`)
- Test: `core/src/test/java/dev/starryeye/organization/core/model/PersonNameTest.java` (신규), `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCaseTest.java`, `admin-api/src/test/java/dev/starryeye/organization/admin/AdminQueryControllerTest.java`

**Interfaces:**
- Produces: `record PersonName(String formatted, String familyName, String givenName, String middleName, String honorificPrefix, String honorificSuffix)` (Lombok `@With`), `static final PersonName EMPTY`, `boolean isEmpty()`
- Produces: `record DirectoryUser(String id, String externalId, String userName, String displayName, String email, boolean active, PersonName name)` (Lombok `@With`) — null `name` → `PersonName.EMPTY`; 6인자 생성자 `DirectoryUser(id, externalId, userName, displayName, email, active)` = 이름 없음
- Produces: `record EmployeeDetail(String employeeId, String userName, String displayName, PersonName name, String email, boolean active, List<AccessPath> paths, boolean truncated)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/model/PersonNameTest.java`:

```java
package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNameTest {

    @Test
    @DisplayName("빈 문자열은 없음으로 본다 — 여섯 칸이 모두 비면 EMPTY 와 같다")
    void 빈_문자열은_없음이다() {
        // when
        PersonName name = new PersonName("", "", "", "", "", "");

        // then
        assertThat(name).isEqualTo(PersonName.EMPTY);
        assertThat(name.isEmpty()).isTrue();
        assertThat(new PersonName(null, "홍", "", null, null, null).givenName()).isNull();
    }

    @Test
    @DisplayName("한 칸이라도 있으면 비어 있지 않다")
    void 한_칸이라도_있으면_비어_있지_않다() {
        // when
        PersonName name = PersonName.EMPTY.withGivenName("길동");

        // then
        assertThat(name.isEmpty()).isFalse();
        assertThat(name.givenName()).isEqualTo("길동");
    }

    @Test
    @DisplayName("직원의 이름이 null 이면 EMPTY 이고, 6인자 생성자는 이름 없는 직원이다")
    void 직원의_이름_기본값() {
        // when
        DirectoryUser 여섯 = new DirectoryUser("kim", null, "kim", "김철수", null, true);
        DirectoryUser 널 = new DirectoryUser("kim", null, "kim", "김철수", null, true, null);

        // then
        assertThat(여섯.name()).isEqualTo(PersonName.EMPTY);
        assertThat(널).isEqualTo(여섯);
    }

    @Test
    @DisplayName("with 로 한 칸만 바꾼 복사는 이름을 지킨다")
    void with_복사는_이름을_지킨다() {
        // given
        PersonName 이름 = new PersonName(null, "홍", "길동", null, null, null);
        DirectoryUser 직원 = new DirectoryUser("hong", null, "hong", "홍길동", null, true, 이름);

        // when, then
        assertThat(직원.withActive(false).name()).isEqualTo(이름);
        assertThat(직원.withId("hong2").name()).isEqualTo(이름);
    }
}
```

`IncrementalSyncUseCaseTest` 에 추가(이 파일의 `state`·`writer`·`useCase` 와 기존 import 를 쓴다. `PersonName`, `DirectoryGroup`, `MemberRef`, `Set` import 가 없으면 더한다):

```java
    @Test
    @DisplayName("반영이 실패해 active 를 되돌려도 요청의 이름은 남는다")
    void 실패해_되돌려도_이름은_남는다() {
        // given — 조직에 속한 활성 직원을 비활성으로 바꾸되 튜플 반영이 전부 실패한다
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV", null, "개발팀", Set.of(MemberRef.user("kim")))).block();
        writer.failFor(tuple -> true);
        PersonName 이름 = new PersonName(null, "김", "철수", null, null, null);

        // when
        useCase.upsertUser(new DirectoryUser("kim", null, "kim", "김철수", null, false, 이름)).block();

        // then
        DirectoryUser 저장 = state.users.get("kim");
        assertThat(저장.active()).isTrue();
        assertThat(저장.name()).isEqualTo(이름);
    }
```

`AdminQueryControllerTest` 에 추가(이 파일의 `state`·`client` 를 쓴다):

```java
    @Test
    @DisplayName("직원 상세에 이름 여섯 칸이 나온다")
    void 직원_상세에_이름이_나온다() {
        // given
        state.saveUser(new DirectoryUser("hong", null, "hong", "홍길동", null, true,
                new PersonName(null, "홍", "길동", null, null, null))).block();

        // when, then
        client.get().uri("/admin/employees/hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name.familyName").isEqualTo("홍")
                .jsonPath("$.name.givenName").isEqualTo("길동");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*PersonNameTest' --tests '*IncrementalSyncUseCaseTest'`
Expected: 컴파일 실패(`PersonName` 없음)

- [ ] **Step 3: 구현한다**

`core/src/main/java/dev/starryeye/organization/core/model/PersonName.java`:

```java
package dev.starryeye.organization.core.model;

import lombok.With;

/**
 * 직원 이름 — RFC 7643 §4.1.1 {@code name} 의 하위 속성 여섯. 튜플에는 쓰지 않는다(권한과 무관하다).
 *
 * <p>빈 문자열은 "없음"(null)으로 본다 — 저장소는 빈 값을 저장하지 않으므로, 여기서 같은 규칙을 지켜야 저장·되읽기 뒤에도
 * 같은 값이 된다. 여섯 칸이 모두 없으면 {@link #EMPTY} 와 같다.
 */
@With
public record PersonName(
        String formatted,
        String familyName,
        String givenName,
        String middleName,
        String honorificPrefix,
        String honorificSuffix
) {

    public static final PersonName EMPTY = new PersonName(null, null, null, null, null, null);

    public PersonName {
        formatted = present(formatted);
        familyName = present(familyName);
        givenName = present(givenName);
        middleName = present(middleName);
        honorificPrefix = present(honorificPrefix);
        honorificSuffix = present(honorificSuffix);
    }

    public boolean isEmpty() {
        return equals(EMPTY);
    }

    private static String present(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
```

`DirectoryUser` 를 다음으로 바꾼다(기존 자바독의 `@param` 두 줄은 남기고 `name` 을 더한다):

```java
package dev.starryeye.organization.core.model;

import lombok.With;

/**
 * @param id 직원 아이디. 튜플에 쓰이는 안정 식별자
 * @param externalId LDAP DN 또는 SCIM externalId (원본 보관)
 * @param name RFC 이름 여섯 칸. 없으면 {@link PersonName#EMPTY}
 *
 * <p><b>필드 하나만 바꾼 복사는 {@code with…} 로 한다.</b> 6인자 생성자로 복사하면 이름이 조용히 사라진다 — 6인자
 * 생성자는 처음부터 이름이 없는 직원을 만들 때만 쓴다.
 */
@With
public record DirectoryUser(
        String id,
        String externalId,
        String userName,
        String displayName,
        String email,
        boolean active,
        PersonName name
) {

    public DirectoryUser {
        name = name == null ? PersonName.EMPTY : name;
    }

    /** 이름이 없는 직원. */
    public DirectoryUser(String id, String externalId, String userName, String displayName, String email,
                         boolean active) {
        this(id, externalId, userName, displayName, email, active, PersonName.EMPTY);
    }
}
```

`IncrementalSyncUseCase` 의 복사 두 곳:

```java
        DirectoryUser neverStored = user.withActive(false);
```

```java
        return requested.withActive(existing.active());
```

(`reconcileUser` 자바독의 "다른 필드는 요청값을 그대로 쓴다" 는 그대로 참이다.)

`EmployeeDetail` — `displayName` 뒤에 `PersonName name` 을 더하고 자바독에 `@param name 이름 여섯 칸. 없는 칸은 null` 을 더한다(`import dev.starryeye.organization.core.model.PersonName;`). `AdminQueryUseCase.toDetail` 의 생성:

```java
                .map(paths -> new EmployeeDetail(user.id(), user.userName(), user.displayName(), user.name(),
                        user.email(), user.active(), paths, reached.truncated));
```

`EmployeeDetail` 을 만드는 다른 곳이 있으면(`grep -rn "new EmployeeDetail(" --include=*.java .`) 같은 순서로 `name` 을 넣는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test :admin-api:test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add core admin-api
git commit -m "feat: 직원 이름 여섯 칸(PersonName) — 복사는 with 로 해 이름을 잃지 않는다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 2: 저장소 — 이름 속성 여섯

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (상수, `userItem`, `toUser`)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`, `DynamoDbDirectoryQueryRepositoryTest.java`

**Interfaces:**
- Consumes: `PersonName`, `DirectoryUser.name()`, 7인자 생성자 (Task 1)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest` 에 추가(파일의 `repository`, `clock`, `meta(pk)`, `updatedAt(pk)`, `세는_저장소(WriteCounter)` 를 쓴다; `PersonName` import):

```java
    private static final PersonName 홍길동 = new PersonName("홍길동", "홍", "길동", "철", "Mr.", "Jr.");

    @Test
    @DisplayName("이름 여섯 칸을 저장하고 되읽는다")
    void 이름을_저장하고_되읽는다() {
        // given
        repository.saveUser(new DirectoryUser("hong", null, "hong", "홍길동", null, true, 홍길동)).block();

        // when
        DirectoryUser 되읽음 = repository.findUser("hong").block();

        // then
        assertThat(되읽음.name()).isEqualTo(홍길동);
        assertThat(meta(Keys.userPk("hong")).get("nameFormatted").s()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("이름이 없으면 이름 속성을 두지 않고, 같은 값을 다시 저장하면 쓰지 않는다")
    void 이름이_없으면_속성이_없다() {
        // given
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        DirectoryUser 이름없음 = new DirectoryUser("kim", null, "kim", "김철수", null, true);
        세는.saveUser(이름없음).block();
        counter.reset();

        // when
        세는.saveUser(이름없음).block();

        // then
        assertThat(meta(Keys.userPk("kim"))).doesNotContainKeys(
                "givenName", "familyName", "middleName", "honorificPrefix", "honorificSuffix", "nameFormatted");
        assertThat(counter.puts()).isZero();
    }

    @Test
    @DisplayName("이름만 바뀌어도 다시 쓰고 updatedAt 이 그 시각이 된다")
    void 이름만_바뀌어도_쓴다() {
        // given
        repository.saveUser(new DirectoryUser("hong", null, "hong", "홍길동", null, true, 홍길동)).block();
        clock.앞으로(Duration.ofHours(1));

        // when
        repository.saveUser(new DirectoryUser("hong", null, "hong", "홍길동", null, true,
                홍길동.withFamilyName("洪"))).block();

        // then
        assertThat(repository.findUser("hong").block().name().familyName()).isEqualTo("洪");
        assertThat(updatedAt(Keys.userPk("hong"))).isEqualTo("2026-01-01T01:00:00Z");
    }
```

`DynamoDbDirectoryQueryRepositoryTest` 에 추가(파일의 `state`·`query` 를 쓴다):

```java
    @Test
    @DisplayName("목록과 userName 일치 조회에도 이름이 실린다 — GSI1 은 속성을 전부 담는다")
    void 목록에도_이름이_실린다() {
        // given
        PersonName 이름 = new PersonName(null, "홍", "길동", null, null, null);
        state.saveUser(new DirectoryUser("hong", "ext-hong", "hong", "홍길동", null, true, 이름)).block();

        // when, then
        assertThat(query.findUsersByUserName("HONG").blockFirst().name()).isEqualTo(이름);
        assertThat(query.listUsers(null, 10, false).block().items().get(0).name()).isEqualTo(이름);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest' --tests '*DynamoDbDirectoryQueryRepositoryTest'`
Expected: 새 테스트 FAIL(이름이 저장되지 않아 되읽으면 EMPTY)

- [ ] **Step 3: 구현한다**

`DynamoDbDirectoryStateRepository` — 상수(기존 `EMAIL` 옆):

```java
    /** 이름 여섯 칸(S-3 설계 §4). {@code formatted} 만 이름을 바꾼 것은 {@code displayName} 과 헷갈리지 않게 하려는 것이다. */
    private static final String GIVEN_NAME = "givenName";
    private static final String FAMILY_NAME = "familyName";
    private static final String MIDDLE_NAME = "middleName";
    private static final String HONORIFIC_PREFIX = "honorificPrefix";
    private static final String HONORIFIC_SUFFIX = "honorificSuffix";
    private static final String NAME_FORMATTED = "nameFormatted";
```

`userItem` 끝(`return item;` 앞)에:

```java
        PersonName name = user.name();
        Attrs.putIfPresent(item, NAME_FORMATTED, name.formatted());
        Attrs.putIfPresent(item, FAMILY_NAME, name.familyName());
        Attrs.putIfPresent(item, GIVEN_NAME, name.givenName());
        Attrs.putIfPresent(item, MIDDLE_NAME, name.middleName());
        Attrs.putIfPresent(item, HONORIFIC_PREFIX, name.honorificPrefix());
        Attrs.putIfPresent(item, HONORIFIC_SUFFIX, name.honorificSuffix());
```

`toUser` 를 7인자로:

```java
    static DirectoryUser toUser(String userId, Map<String, AttributeValue> item) {
        return new DirectoryUser(
                userId,
                Attrs.str(item, EXTERNAL_ID),
                Attrs.str(item, USER_NAME),
                Attrs.str(item, DISPLAY_NAME),
                Attrs.str(item, EMAIL),
                Attrs.flag(item, ACTIVE),
                new PersonName(
                        Attrs.str(item, NAME_FORMATTED),
                        Attrs.str(item, FAMILY_NAME),
                        Attrs.str(item, GIVEN_NAME),
                        Attrs.str(item, MIDDLE_NAME),
                        Attrs.str(item, HONORIFIC_PREFIX),
                        Attrs.str(item, HONORIFIC_SUFFIX)));
    }
```

(`import dev.starryeye.organization.core.model.PersonName;`. "바뀐 것만 쓴다" 비교는 `userItem`·`toUser` 를 거치므로 따로 고칠 것이 없다.)

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add storage-dynamodb
git commit -m "feat: 직원 META 에 이름 여섯 칸을 저장한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 3: LDAP — 표준 이름 속성을 읽는다

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapPersonName.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java` (`UserEntry`, `userMapper`, `read` 의 `new DirectoryUser`)
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/DitStrategy.java` (직원 생성)
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapPersonNameTest.java` (신규)

**Interfaces:**
- Consumes: `PersonName`, 7인자 `DirectoryUser` (Task 1)
- Produces: 패키지 전용 `final class LdapPersonName` — `static PersonName from(Attributes attributes)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapPersonNameTest.java` — `GroupOfNamesAccountStatusTest` 와 같은 `EmbeddedLdapSupport` 구조(같은 import, `ldif()` 재정의, `ldapTemplate` 필드)를 쓴다. 두 전략의 설정은 그 파일과 `DitAccountStatusTest` 가 만드는 방식 그대로 쓴다:

```java
/** LDAP 표준 이름 속성을 직원 이름으로 읽는다(S-3 설계 §5). 속성 이름은 설정으로 빼지 않는다. */
class LdapPersonNameTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=people,dc=example,dc=com
                objectClass: organizationalUnit
                ou: people

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: uid=hong,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: hong
                cn: 홍길동
                sn: 홍
                givenName: 길동
                middleName: 철
                generationQualifier: Jr.

                dn: uid=plain,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: plain
                cn: plain
                sn: plain

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: uid=hong,ou=people,dc=example,dc=com
                member: uid=plain,ou=people,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("groupOfNames 전략이 givenName·sn·middleName·generationQualifier 를 이름으로 읽는다")
    void groupOfNames_가_이름을_읽는다() {
        // given — GroupOfNamesAccountStatusTest 와 같은 설정으로 전략을 만든다
        var properties = new LdapProperties();
        properties.setBaseDn("dc=example,dc=com");
        properties.getGroupOfNames().setUserSearchBase("ou=people");
        properties.getGroupOfNames().setGroupSearchBase("ou=groups");

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users().get("hong").name())
                .isEqualTo(new PersonName(null, "홍", "길동", "철", null, "Jr."));
        assertThat(snapshot.users().get("plain").name())
                .isEqualTo(new PersonName(null, "plain", null, null, null, null));
    }
}
```

DIT 전략용 같은 테스트도 이 클래스에 더한다 — `DitAccountStatusTest` 가 DIT 전략을 만드는 설정(루트 DN, `ou` 트리)을 그대로 쓰되, 직원 엔트리를 `ou` 아래에 두는 LDIF 가 필요하면 `DitLdapPersonNameTest` 라는 두 번째 클래스로 나누고 `DitAccountStatusTest` 의 LDIF 모양을 따른다. 기대값은 위와 같다(`hong` → `(null, "홍", "길동", "철", null, "Jr.")`, 이름 속성이 `sn` 뿐인 직원 → `familyName` 만).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*PersonName*'`
Expected: 컴파일 실패(`LdapPersonName`/`name()` 이 없음) 또는 이름이 EMPTY 라 FAIL

- [ ] **Step 3: 구현한다**

`connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapPersonName.java`:

```java
package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.PersonName;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;

/**
 * LDAP 표준 이름 속성을 직원 이름으로 읽는다 (S-3 설계 §5). 속성 이름은 표준이라 설정으로 빼지 않는다 — ⑥ 의 AD 계정
 * 상태와 같은 방식이다.
 *
 * <ul>
 *   <li>{@code givenName} → givenName, {@code sn} → familyName, {@code generationQualifier} → honorificSuffix (RFC 4519)</li>
 *   <li>{@code middleName} → middleName (AD 스키마)</li>
 * </ul>
 * {@code formatted}·{@code honorificPrefix} 는 LDAP 표준 속성이 없어 비운다. 속성이 없으면 빈칸이다. 여러 값이면 첫 값이다.
 */
final class LdapPersonName {

    private LdapPersonName() {
    }

    static PersonName from(Attributes attributes) {
        return new PersonName(
                null,
                first(attributes, "sn"),
                first(attributes, "givenName"),
                first(attributes, "middleName"),
                null,
                first(attributes, "generationQualifier"));
    }

    private static String first(Attributes attributes, String name) {
        Attribute attribute = attributes.get(name);
        if (attribute == null) {
            return null;
        }
        try {
            Object value = attribute.get();
            return value == null ? null : value.toString();
        } catch (NamingException e) {
            throw new DirectoryDataException("속성 '" + name + "' 을 읽지 못했습니다", e);
        }
    }
}
```

`GroupOfNamesStrategy`:
- `record UserEntry(String id, String dn, String displayName, String email, boolean active)` 에 `PersonName name` 을 끝에 더한다.
- `userMapper` 의 `new UserEntry(...)` 마지막 인자 뒤에 `LdapPersonName.from(attributes)` 를 더한다.
- `read` 의 직원 생성을 `new DirectoryUser(entry.id(), entry.dn(), entry.id(), entry.displayName(), entry.email(), entry.active(), entry.name())` 로.

`DitStrategy` 의 직원 생성 — 마지막 인자(`!AdAccountStatus.막혔는가(...)`) 뒤에 `LdapPersonName.from(entry.adapter().getAttributes())` 를 더해 7인자로.

(두 파일에 `import dev.starryeye.organization.core.model.PersonName;` 가 필요하면 더한다.)

- [ ] **Step 4: 모듈 전체를 확인한다**

Run: `./gradlew :connector-ldap:test`
Expected: 전부 PASS. **기존 LDAP 픽스처는 `sn` 을 가지므로 이제 `familyName` 이 채워진다** — 직원을 `DirectoryUser` 통째로 비교하는 기존 테스트가 실패하면 기대값에 이름을 넣어 고치고, 고친 테스트를 보고서에 적는다(필드별 비교 테스트는 영향이 없다).

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add connector-ldap
git commit -m "feat: LDAP 표준 이름 속성(givenName·sn·middleName·generationQualifier)을 읽는다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 4: SCIM — 생성·교체·응답

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimName.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java` (`toDirectoryUser`, `toScimUser`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimResourceType.java` (USER 속성 목록)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java` (`replace` 의 복사)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimMapperTest.java`, `ScimUserHandlerTest.java`

**Interfaces:**
- Consumes: `PersonName`, `DirectoryUser.withId` (Task 1)
- Produces: `record ScimName(String formatted, String familyName, String givenName, String middleName, String honorificPrefix, String honorificSuffix)`; `static PersonName ScimMapper.toPersonName(ScimName)`(null → EMPTY), `static ScimName ScimMapper.toScimName(PersonName)`(EMPTY → null)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimMapperTest` 에 추가:

```java
    @Test
    @DisplayName("name 여섯 칸을 보낸 그대로 담고 그대로 돌려준다")
    void 이름을_그대로_담고_돌려준다() {
        // given
        ScimUser scim = new ScimUser(List.of(ScimSchemas.USER), null, "e1", "hong",
                new ScimName("홍길동", "홍", "길동", "철", "Mr.", "Jr."), null, null, true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);
        ScimUser 응답 = ScimMapper.toScimUser(user);

        // then
        assertThat(user.name()).isEqualTo(new PersonName("홍길동", "홍", "길동", "철", "Mr.", "Jr."));
        assertThat(user.displayName()).isEqualTo("홍길동");
        assertThat(응답.name()).isEqualTo(new ScimName("홍길동", "홍", "길동", "철", "Mr.", "Jr."));
    }

    @Test
    @DisplayName("이름이 없으면 응답에 name 을 넣지 않는다 — formatted 를 지어내지 않는다")
    void 이름이_없으면_name_이_없다() {
        // when
        ScimUser 응답 = ScimMapper.toScimUser(new DirectoryUser("kim", null, "kim", "김철수", null, true));

        // then
        assertThat(응답.name()).isNull();
        assertThat(응답.displayName()).isEqualTo("김철수");
    }
```

`ScimUserHandlerTest` 에 추가(파일의 `client`·`state` 를 쓴다):

```java
    @Test
    @DisplayName("PUT 은 경로의 id 로 고정하되 본문의 이름을 담는다")
    void PUT_은_이름을_담는다() {
        // given
        state.saveUser(new DirectoryUser("hong", null, "hong", "홍길동", null, true)).block();

        // when, then
        client.put().uri("/scim/v2/Users/hong")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"hong",
                         "name":{"familyName":"홍","givenName":"길동"},"active":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("hong")
                .jsonPath("$.name.familyName").isEqualTo("홍")
                .jsonPath("$.name.givenName").isEqualTo("길동");
        assertThat(state.users.get("hong").name().givenName()).isEqualTo("길동");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimMapperTest' --tests '*ScimUserHandlerTest'`
Expected: 컴파일 실패(`ScimName` 이 3인자)

- [ ] **Step 3: 구현한다**

`ScimName`:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimName(String formatted, String familyName, String givenName,
                       String middleName, String honorificPrefix, String honorificSuffix) {
}
```

`ScimName` 을 3인자로 만드는 다른 곳(`grep -rn "new ScimName(" connector-scim app-scim`)은 6인자로 고친다(없는 칸은 null).

`ScimMapper`(`import dev.starryeye.organization.core.model.PersonName;`):

```java
    public static DirectoryUser toDirectoryUser(ScimUser scim) {
        if (scim.userName() == null || scim.userName().isBlank()) {
            throw ScimException.invalidSyntax("userName 은 필수입니다");
        }
        return new DirectoryUser(
                IdNormalizer.normalize(scim.userName()),
                scim.externalId(),
                scim.userName(),
                firstNonBlank(scim.displayName(), formatted(scim), scim.userName()),
                primaryEmail(scim.emails()),
                // SCIM 에서 active 는 선택 필드다. 없으면 활성으로 본다.
                scim.active() == null || scim.active(),
                toPersonName(scim.name()));
    }

    /** 보낸 그대로 담는다(S-3 설계 §7.1). 없으면 이름 없음. */
    public static PersonName toPersonName(ScimName name) {
        if (name == null) {
            return PersonName.EMPTY;
        }
        return new PersonName(name.formatted(), name.familyName(), name.givenName(),
                name.middleName(), name.honorificPrefix(), name.honorificSuffix());
    }

    /** 저장된 그대로 돌려준다. 이름이 없으면 null — 응답에 {@code name} 을 넣지 않는다. */
    public static ScimName toScimName(PersonName name) {
        if (name.isEmpty()) {
            return null;
        }
        return new ScimName(name.formatted(), name.familyName(), name.givenName(),
                name.middleName(), name.honorificPrefix(), name.honorificSuffix());
    }
```

`toScimUser` 의 `new ScimName(user.displayName(), null, null)` 을 `toScimName(user.name())` 로 바꾼다.

`ScimResourceType.USER` 의 속성 집합에 `"name.middlename", "name.honorificprefix", "name.honorificsuffix"` 를 더한다.

`ScimUserHandler.replace` 의 복사를 `with` 로:

```java
                // PUT 은 경로의 id 를 정본으로 삼는다. 본문의 userName 이 달라도 리소스를 옮기지 않는다.
                .map(user -> user.withId(id))
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: 전부 PASS. 기존 테스트 중 응답의 `name.formatted` 가 `displayName` 이라고 가정한 것이 있으면, 스펙 §7.1(이름이 없으면 `name` 없음)에 맞게 기대값을 고치고 보고서에 적는다.

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add connector-scim
git commit -m "feat: SCIM 직원 name 여섯 칸을 보낸 그대로 담고 돌려준다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 5: SCIM — 직원 PATCH 경로와 대소문자 무시

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimException.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java`, `ScimUserHandlerTest.java`

**Interfaces:**
- Consumes: `PersonName` (`@With`), `DirectoryUser` (`@With`) (Task 1)
- Produces: `ScimException.mutability(String)` (400 `mutability`), `ScimException.noTarget(String)` (400 `noTarget`)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimPatchApplierTest` 에 추가(파일의 `패치(op, path, value)` 도우미를 쓴다; `PersonName`, `HttpStatus`, `assertThatThrownBy` import):

```java
    private static final PersonName 홍길동 = new PersonName("홍길동", "홍", "길동", null, null, null);

    private static DirectoryUser 이름있는_직원() {
        return new DirectoryUser("hong", "emp-1", "hong", "홍길동", "hong@example.com", true, 홍길동);
    }

    private static ScimPatchOp 연산들(ScimOperation... operations) {
        return new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(operations));
    }

    private static void 거절한다(ScimPatchOp patch, String scimType) {
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(이름있는_직원(), patch))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo(scimType);
                });
    }

    @Test
    @DisplayName("Entra 문서의 PATCH 예시 — 이메일과 성을 한 요청에서 바꾼다")
    void Entra_의_이메일과_성_PATCH() {
        // given — learn.microsoft.com "Develop a SCIM endpoint" 의 Update User [Multi-valued properties] 예시
        ScimPatchOp patch = 연산들(
                new ScimOperation("Replace", "emails[type eq \"work\"].value", "updatedEmail@microsoft.com"),
                new ScimOperation("Replace", "name.familyName", "updatedFamilyName"));

        // when
        DirectoryUser after = ScimPatchApplier.applyToUser(이름있는_직원(), patch);

        // then
        assertThat(after.email()).isEqualTo("updatedEmail@microsoft.com");
        assertThat(after.name()).isEqualTo(홍길동.withFamilyName("updatedFamilyName"));
    }

    @Test
    @DisplayName("name 은 준 하위 속성만 바꾸고 나머지는 둔다, remove 는 전부 비운다")
    void name_은_하위_속성만_바꾼다() {
        // when
        DirectoryUser 병합 = ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", "name", Map.of("givenName", "길순", "middleName", "가")));
        DirectoryUser 비움 = ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "name", null));

        // then
        assertThat(병합.name()).isEqualTo(new PersonName("홍길동", "홍", "길순", "가", null, null));
        assertThat(비움.name()).isEqualTo(PersonName.EMPTY);
    }

    @Test
    @DisplayName("name.* 여섯 칸은 설정과 remove 를 받는다")
    void name_하위_경로() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("add", "name.honorificPrefix", "Mr."))
                .name().honorificPrefix()).isEqualTo("Mr.");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "name.givenName", null))
                .name().givenName()).isNull();
        거절한다(패치("replace", "name.nickName", "x"), "invalidPath");
    }

    @Test
    @DisplayName("externalId·displayName 은 설정과 remove, active 의 remove 는 활성이다")
    void externalId_displayName_active() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", "externalId", "emp-2"))
                .externalId()).isEqualTo("emp-2");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "externalId", null))
                .externalId()).isNull();
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "displayName", null))
                .displayName()).isNull();
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원().withActive(false), 패치("remove", "active", null))
                .active()).isTrue();
    }

    @Test
    @DisplayName("userName 은 필수라 remove 하면 400 mutability 다")
    void userName_remove_는_mutability() {
        거절한다(패치("remove", "userName", null), "mutability");
    }

    @Test
    @DisplayName("emails 는 primary(없으면 첫째)를 담고, work 필터는 add·replace·remove 를 받는다")
    void 이메일_경로() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", "emails", List.of(
                Map.of("value", "a@x.com"), Map.of("value", "b@x.com", "primary", true)))).email())
                .isEqualTo("b@x.com");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "emails", null)).email()).isNull();
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", "emails[type eq \"work\"]", Map.of("value", "c@x.com", "type", "work"))).email())
                .isEqualTo("c@x.com");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("remove", "emails[type eq \"work\"].value", null)).email()).isNull();
    }

    @Test
    @DisplayName("이메일이 없을 때 work 필터 replace 는 400 noTarget, add 는 설정한다")
    void 이메일이_없으면_replace_는_noTarget() {
        // given
        DirectoryUser 메일없음 = 이름있는_직원().withEmail(null);

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(메일없음,
                패치("replace", "emails[type eq \"work\"].value", "a@x.com")))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("noTarget"));
        assertThat(ScimPatchApplier.applyToUser(메일없음,
                패치("add", "emails[type eq \"work\"].value", "a@x.com")).email()).isEqualTo("a@x.com");
    }

    @Test
    @DisplayName("work 가 아닌 이메일 필터와 저장하지 않는 속성은 400 invalidPath 다")
    void 저장하지_않는_경로는_invalidPath() {
        거절한다(패치("replace", "emails[type eq \"home\"].value", "a@x.com"), "invalidPath");
        거절한다(패치("replace", "title", "과장"), "invalidPath");
        거절한다(패치("replace", "phoneNumbers[type eq \"mobile\"].value", "010"), "invalidPath");
        거절한다(패치("replace", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department", "개발"),
                "invalidPath");
    }

    @Test
    @DisplayName("경로와 값 객체의 속성 이름은 대소문자를 가리지 않는다")
    void 속성_이름은_대소문자를_가리지_않는다() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("Replace", "Name.GivenName", "길순"))
                .name().givenName()).isEqualTo("길순");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", "EMAILS[TYPE EQ \"Work\"].VALUE", "d@x.com")).email()).isEqualTo("d@x.com");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", null,
                Map.of("DisplayName", "홍", "ExternalId", "e9", "Name", Map.of("FamilyName", "洪")))))
                .satisfies(user -> {
                    assertThat(user.displayName()).isEqualTo("홍");
                    assertThat(user.externalId()).isEqualTo("e9");
                    assertThat(user.name().familyName()).isEqualTo("洪");
                    assertThat(user.name().givenName()).isEqualTo("길동");
                });
    }

    @Test
    @DisplayName("조직 PATCH 경로도 대소문자를 가리지 않는다")
    void 조직_경로도_대소문자를_가리지_않는다() {
        // given
        var before = 조직(MemberRef.user("lee"), MemberRef.user("kim"));

        // when
        var 추가 = ScimPatchApplier.applyToGroup(before,
                패치("add", "Members", List.of(멤버("park", "User"))), USER_ONLY).block();
        var 제거 = ScimPatchApplier.applyToGroup(before,
                패치("remove", "MEMBERS[VALUE EQ \"lee\"]", null), USER_ONLY).block();
        var 이름 = ScimPatchApplier.applyToGroup(before, 패치("replace", "DisplayName", "새 팀"), USER_ONLY).block();

        // then
        assertThat(추가.members()).contains(MemberRef.user("park"));
        assertThat(제거.members()).containsExactly(MemberRef.user("kim"));
        assertThat(이름.displayName()).isEqualTo("새 팀");
    }
```

`ScimUserHandlerTest` 에 HTTP 로 Entra 예시를 보내는 테스트:

```java
    @Test
    @DisplayName("Entra 의 이메일+성 PATCH 가 200 이고 둘 다 반영된다")
    void Entra_PATCH_가_200() {
        // given
        state.saveUser(new DirectoryUser("hong", null, "hong", "홍길동", "old@example.com", true,
                new PersonName(null, "홍", "길동", null, null, null))).block();

        // when, then
        client.patch().uri("/scim/v2/Users/hong")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[
                           {"op":"Replace","path":"emails[type eq \\"work\\"].value","value":"updatedEmail@microsoft.com"},
                           {"op":"Replace","path":"name.familyName","value":"updatedFamilyName"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.emails[0].value").isEqualTo("updatedEmail@microsoft.com")
                .jsonPath("$.name.familyName").isEqualTo("updatedFamilyName")
                .jsonPath("$.name.givenName").isEqualTo("길동");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimPatchApplierTest' --tests '*ScimUserHandlerTest'`
Expected: 새 테스트 FAIL(지원하지 않는 path, 대소문자)

- [ ] **Step 3: 구현한다**

`ScimException` — 기존 팩토리 옆에:

```java
    /** RFC 7644 §3.5.2.2 — 필수·읽기 전용 속성을 지우려 했다. */
    public static ScimException mutability(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "mutability", detail);
    }

    /** RFC 7644 §3.5.2.3 — 값 경로 필터가 가리킨 값이 없다. */
    public static ScimException noTarget(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "noTarget", detail);
    }
```

`ScimPatchApplier`:

1. 조직 쪽 — `MEMBER_VALUE_FILTER` 에 `Pattern.CASE_INSENSITIVE` 를 준다. `path.trim().equals("members")` 와 `path.trim().equals("displayName")` 을 `equalsIgnoreCase` 로. `mergeGroupAttributes` 의 키 조회를 아래 `attribute(map, name)` 도우미로(대소문자 무시).
2. 클래스 자바독의 "설계 §10.1 이 정한 여섯 가지 형태만" 줄을 "직원은 우리가 저장하는 속성 전부, 조직은 members·displayName(S-3 설계 §7.2). 속성 이름은 대소문자를 가리지 않는다" 로 고친다.
3. 직원 쪽을 다음으로 바꾼다(`import dev.starryeye.organization.core.model.PersonName;`, `java.util.Locale`, `java.util.function.BiFunction`):

```java
    /** {@code emails[type eq "work"]} 와 {@code .value} — 우리는 이메일을 하나만 담고 type "work" 로 내보낸다. */
    private static final Pattern EMAIL_FILTER = Pattern.compile(
            "^emails\\[\\s*type\\s+eq\\s+\"(?<type>[^\"]*)\"\\s*](?<value>\\.value)?$", Pattern.CASE_INSENSITIVE);

    /** {@code name} 의 하위 속성 여섯. 키는 소문자. */
    private static final Map<String, BiFunction<PersonName, String, PersonName>> NAME_PARTS = Map.of(
            "formatted", PersonName::withFormatted,
            "familyname", PersonName::withFamilyName,
            "givenname", PersonName::withGivenName,
            "middlename", PersonName::withMiddleName,
            "honorificprefix", PersonName::withHonorificPrefix,
            "honorificsuffix", PersonName::withHonorificSuffix);

    private static DirectoryUser applyOne(DirectoryUser user, ScimOperation operation) {
        String op = requireKnownOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireReplaceOrAdd(op, operation.op());
            return mergeUserAttributes(user, asAttributeMap(operation.value()));
        }

        String target = path.trim();
        Matcher email = EMAIL_FILTER.matcher(target);
        if (email.matches()) {
            return applyWorkEmail(user, op, operation, email, path);
        }

        boolean remove = op.equals("remove");
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name.")) {
            BiFunction<PersonName, String, PersonName> part = NAME_PARTS.get(lower.substring("name.".length()));
            if (part == null) {
                throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
            }
            return user.withName(part.apply(user.name(), remove ? null : asString(operation.value())));
        }
        return switch (lower) {
            case "username" -> {
                if (remove) {
                    throw ScimException.mutability("userName 은 필수라 지울 수 없습니다");
                }
                yield user.withUserName(asString(operation.value()));
            }
            case "displayname" -> user.withDisplayName(remove ? null : asString(operation.value()));
            case "externalid" -> user.withExternalId(remove ? null : asString(operation.value()));
            // active 가 없으면 활성이다 — POST 에 active 가 없을 때와 같은 규칙
            case "active" -> user.withActive(remove || asBoolean(operation.value()));
            case "name" -> user.withName(remove ? PersonName.EMPTY : mergeName(user.name(), asAttributeMap(operation.value())));
            case "emails" -> user.withEmail(remove ? null : primaryEmail(operation.value()));
            default -> throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
        };
    }

    /** RFC 7644 §3.5.2.3 — 이메일이 없는데 replace 하면 가리킬 값이 없다. add 는 새로 담는다. */
    private static DirectoryUser applyWorkEmail(DirectoryUser user, String op, ScimOperation operation,
                                                Matcher filter, String path) {
        if (!filter.group("type").equalsIgnoreCase("work")) {
            throw ScimException.invalidPath("이메일은 type \"work\" 하나만 담습니다: " + path);
        }
        if (op.equals("remove")) {
            return user.withEmail(null);
        }
        if (op.equals("replace") && user.email() == null) {
            throw ScimException.noTarget("바꿀 work 이메일이 없습니다: " + path);
        }
        String value = filter.group("value") != null
                ? asString(operation.value())
                : asString(attribute(asAttributeMap(operation.value()), "value"));
        return user.withEmail(value);
    }

    /** 경로 없는 add/replace — 우리가 저장하는 속성 전부를 반영한다. 모르는 키는 POST 처럼 무시한다. */
    private static DirectoryUser mergeUserAttributes(DirectoryUser user, Map<String, Object> attributes) {
        DirectoryUser merged = user;
        if (has(attributes, "userName")) {
            merged = merged.withUserName(asString(attribute(attributes, "userName")));
        }
        if (has(attributes, "displayName")) {
            merged = merged.withDisplayName(asString(attribute(attributes, "displayName")));
        }
        if (has(attributes, "externalId")) {
            merged = merged.withExternalId(asString(attribute(attributes, "externalId")));
        }
        if (has(attributes, "active")) {
            merged = merged.withActive(asBoolean(attribute(attributes, "active")));
        }
        if (has(attributes, "name")) {
            merged = merged.withName(mergeName(merged.name(), asAttributeMap(attribute(attributes, "name"))));
        }
        if (has(attributes, "emails")) {
            merged = merged.withEmail(primaryEmail(attribute(attributes, "emails")));
        }
        return merged;
    }

    /** RFC 7644 §3.5.2.3 — 준 하위 속성만 바꾸고 나머지는 그대로 둔다. 모르는 하위 속성은 무시한다. */
    private static PersonName mergeName(PersonName current, Map<String, Object> parts) {
        PersonName merged = current;
        for (Map.Entry<String, Object> entry : parts.entrySet()) {
            BiFunction<PersonName, String, PersonName> part = NAME_PARTS.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (part != null) {
                merged = part.apply(merged, asString(entry.getValue()));
            }
        }
        return merged;
    }

    /** POST 와 같은 규칙 — primary 가 참인 것, 없으면 첫째. 빈 목록이면 없음. */
    private static String primaryEmail(Object value) {
        if (!(value instanceof List<?> emails)) {
            throw ScimException.invalidSyntax("emails 값은 배열이어야 합니다");
        }
        Map<String, Object> chosen = null;
        for (Object element : emails) {
            Map<String, Object> email = asAttributeMap(element);
            if (chosen == null || Boolean.TRUE.equals(attribute(email, "primary"))) {
                chosen = email;
                if (Boolean.TRUE.equals(attribute(email, "primary"))) {
                    break;
                }
            }
        }
        return chosen == null ? null : asString(attribute(chosen, "value"));
    }

    /** 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1). */
    private static Object attribute(Map<String, Object> attributes, String name) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean has(Map<String, Object> attributes, String name) {
        return attributes.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private static String requireKnownOp(String op) {
        String normalized = normalizeOp(op);
        if (!normalized.equals("add") && !normalized.equals("replace") && !normalized.equals("remove")) {
            throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + op);
        }
        return normalized;
    }
```

`asAttributeMap` 의 메시지("path 없는 연산의 값은 객체여야 합니다")는 이제 여러 곳에서 쓰이므로 "값은 객체여야 합니다" 로 바꾼다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: 전부 PASS. 기존 `ScimPatchApplierTest` 가 "지원하지 않는 path" 로 기대하던 경로(예: `name.familyName`, `emails`)가 있으면 새 규칙(스펙 §7.2)에 맞게 고치고 보고서에 적는다.

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add connector-scim
git commit -m "feat: 직원 PATCH 가 저장하는 속성 전부를 받는다 — 이름·이메일·externalId, 경로 대소문자 무시" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 6: E2E 와 README

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimNameEndToEndTest.java`
- Modify: `README.md` (SCIM 절의 PATCH 표·"path 는 대소문자를 구분한다" 문장·저장하는 속성, LDAP 절)

**Interfaces:**
- Consumes: 앱 전체(Task 1~5)

- [ ] **Step 1: E2E 테스트를 쓴다**

클래스 선언부(애노테이션, `OPENFGA`·`DYNAMODB` 컨테이너, `@DynamicPropertySource`)는 `ScimQueryEndToEndTest` 의 것을 그대로 옮긴다. 필드는 `@Autowired WebTestClient client;`. 본문:

```java
    @Test
    @Order(1)
    @DisplayName("Okta 식 생성 — 성·이름을 담아 만들면 GET 과 userName 조회에 그대로 나온다")
    void Okta_식_생성() {
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"hong@example.com","name":{"givenName":"길동","familyName":"홍"},
                         "emails":[{"value":"hong@example.com","primary":true}],"active":true}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name.givenName").isEqualTo("길동")
                .jsonPath("$.name.formatted").doesNotExist();

        client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"HONG@example.com\"")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].name.familyName").isEqualTo("홍")
                .jsonPath("$.Resources[0].name.givenName").isEqualTo("길동");
    }

    @Test
    @Order(2)
    @DisplayName("Entra 식 PATCH — 이메일과 성을 한 요청에서 바꾸면 200 이고 둘 다 반영된다")
    void Entra_식_PATCH() {
        client.patch().uri("/scim/v2/Users/hong@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[
                           {"op":"Replace","path":"emails[type eq \\"work\\"].value","value":"updatedEmail@microsoft.com"},
                           {"op":"Replace","path":"name.familyName","value":"updatedFamilyName"}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/scim/v2/Users/hong@example.com")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.emails[0].value").isEqualTo("updatedEmail@microsoft.com")
                .jsonPath("$.name.familyName").isEqualTo("updatedFamilyName")
                .jsonPath("$.name.givenName").isEqualTo("길동");
    }

    @Test
    @Order(3)
    @DisplayName("저장하지 않는 속성을 경로로 PATCH 하면 400 invalidPath 이고 아무것도 바뀌지 않는다")
    void 저장하지_않는_속성은_거절한다() {
        client.patch().uri("/scim/v2/Users/hong@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[
                           {"op":"Replace","path":"name.givenName","value":"길순"},
                           {"op":"Replace","path":"title","value":"과장"}]}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidPath");

        client.get().uri("/scim/v2/Users/hong@example.com")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.name.givenName").isEqualTo("길동");
    }
```

(직원 id 는 `userName` 을 `IdNormalizer` 로 정규화한 값이다 — `hong@example.com` 에는 금지 문자가 없어 그대로다.)

- [ ] **Step 2: 실행한다**

Run: `./gradlew :app-scim:test --tests '*ScimNameEndToEndTest'`
Expected: PASS (Task 1~5 가 끝났으므로 처음부터 통과해야 한다)

- [ ] **Step 3: README 를 고친다**

SCIM 절의 PATCH 표를 다음으로 바꾼다:

```markdown
| 대상 | `path` | 지원 `op` |
|---|---|---|
| Group | `members` | `add` / `remove`(전체 비움) / `replace` |
| Group | `members[value eq "..."]` | `remove` |
| Group | `displayName` | `replace` / `add` |
| Group | (path 없음) | `replace` / `add` — 본문을 부분 리소스로 보고 `displayName`·`members`만 병합 |
| User | `userName` | `replace` / `add` (`remove` 는 400 `mutability` — 필수 속성) |
| User | `displayName` / `externalId` / `active` | `replace` / `add` / `remove`(비움. `active` 는 "없음" = 활성) |
| User | `name`, `name.givenName`·`familyName`·`middleName`·`formatted`·`honorificPrefix`·`honorificSuffix` | `replace` / `add`(`name` 은 준 하위 속성만 바꿈) / `remove` |
| User | `emails` / `emails[type eq "work"]` / `emails[type eq "work"].value` | `replace` / `add` / `remove` — 이메일이 없는데 `replace` 하면 400 `noTarget` |
| User | (path 없음) | `replace` / `add` — 위 속성 전부를 병합 |
```

그 아래 "`path`는 대소문자를 구분한다." 문장을 다음으로 바꾼다:

```markdown
`op` 와 `path` 의 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1).

**우리가 저장하는 직원 속성은** `userName`, `displayName`, `externalId`, `active`, `name`(여섯 칸), 이메일 하나(`type: "work"`)
뿐이다. `title`, `phoneNumbers`, `addresses`, 엔터프라이즈 확장(`department`, `manager` 등)을 경로로 PATCH 하면 400
`invalidPath` 다 — 반영되지 않은 변경을 반영됐다고 IdP 가 오해하지 않게 하려는 것이다. **IdP 의 속성 매핑에서 이 속성들을 뺀다**
(Entra 는 기본 매핑에 넣을 수 있다).
```

LDAP 절의 계정 상태 설명 뒤에:

```markdown
**이름은 표준 속성에서 읽는다** — `givenName`→이름, `sn`→성, `generationQualifier`→접미(Jr. 등), AD 의 `middleName`→중간
이름. 속성이 없으면 빈칸이다. admin 직원 상세(`GET /admin/employees/{id}`)의 `name` 에 나온다.
```

- [ ] **Step 4: 커밋하고 푸시한다**

```bash
git add app-scim/src/test README.md
git commit -m "test: 이름·이메일 E2E — Okta 식 생성과 Entra 식 PATCH, 문서에 저장하는 속성" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 **둘 다** 돌리고 소요 시간을 스펙 §9 에 적는다. Gradle 은 하나씩.
- 사용자에게 결과를 보고하고 머지 여부를 묻는다.

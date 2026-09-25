# SCIM 목록·필터 조회 (S-1) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entra·Okta 가 프로비저닝을 시작할 수 있도록 `GET /Users`·`GET /Groups` 목록·필터 조회(RFC 7644 §3.4.2)를 직원 10만 명 규모에서 페이지당 `count` 건만 읽게 제공한다.

**Architecture:** 저장소에 두 읽기 포트를 더한다 — 완전 일치·인덱스 위치 읽기(`DirectoryQueryRepository`, GSI1 소문자 키 + 새 GSI3)와 페이지 책갈피(`PageBookmarkRepository`, TTL 15분). `connector-scim` 은 좁은 필터 파서(`eq`+`and`), 조회 파라미터(`ScimQuery`), 속성 선택(`ScimAttributeProjection`), 조회 엔진(`ScimUserListing`·`ScimGroupListing`)을 두고 라우트에 목록·`.search` 를 얹는다.

**Tech Stack:** Java 17, Spring WebFlux(함수형 라우트), Reactor, AWS SDK v2 DynamoDB(async), Jackson, JUnit 5, AssertJ, Testcontainers(DynamoDB Local, OpenFGA).

**Spec:** [`docs/superpowers/specs/2026-09-25-scim-list-filter-design.md`](../specs/2026-09-25-scim-list-filter-design.md)

## Global Constraints

- 테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석. 기존 테스트의 형태를 그대로 따른다. Lombok 을 쓴다.
- **필터(스펙 §4.1):** `filter = term *( SP "and" SP term )`, `term = attrPath SP "eq" SP compValue`, 값은 JSON 문자열 또는 `true`/`false`. `and`·`eq`·속성 이름은 대소문자 무시, 공백은 한 칸. 그 밖은 전부 400 `invalidFilter`.
- **속성(스펙 §4.2):** User — `id`(caseExact, GetItem), `userName`(대소문자 무시, GSI1), `externalId`(caseExact, GSI3), `displayName`(대소문자 무시, `and` 뒤만), `active`(불리언, `and` 뒤만). Group — `id`, `displayName`(대소문자 무시, GSI1), `externalId`(caseExact, GSI3). 인덱스 조건 우선순위: `id` → `userName`/`displayName` → `externalId`.
- **대소문자 무시 비교는 `toLowerCase(Locale.ROOT)` 하나로 한다** — 인덱스 키(`Keys.indexKey`)와 메모리 비교가 같은 규칙이어야 한다.
- **페이지(스펙 §4.4):** `startIndex<1`→1, `count` 없음→100, `count<0`→0, `count>100`→100. 정수 아님→400 `invalidValue`. `count=0` 이면 `Resources` 없음, 결과 0건이면 `Resources: []`.
- **정렬(스펙 §4.3):** `sortBy` 는 User `userName`, Group `displayName` 만(URN 이름 포함). `sortOrder` 는 `ascending`/`descending`(대소문자 무시). 그 밖은 400 `invalidValue`.
- **책갈피 TTL 은 15분**, 키는 `(ListingKind, descending, startIndex)`. 읽을 때 `expiresAt` 이 지났으면 없는 것으로 본다.
- **Gradle 규칙:** 서브에이전트는 자기 과제의 모듈 테스트만 포그라운드로 돌린다. 루트 `test`·`build`·`check`·`scaleTest` 전체는 돌리지 않는다. Gradle 을 둘 이상 동시에 돌리지 않는다.
- 커밋마다 푸시한다(`git push`). 커밋 트레일러: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## 스펙과 다르게 정한 것 (계획 작성 시 판정)

- **`IndexPage` 대신 기존 `core.query.Page<T>(items, nextCursor)` 를 쓴다** — 스펙 §5.3 의 `IndexPage` 와 모양이 같은 타입이 이미 있다. 같은 개념에 타입 둘을 두지 않는다.
- **`excludedAttributes=members` 가 멤버를 읽지 않음은 connector-scim 단위 테스트에서 `FakeStateRepository.findGroupCalls` 로 확인한다** — 스펙 §8.3 은 E2E `@MockitoSpyBean` 을 적었지만, 판단하는 코드(핸들러·조회 엔진)가 같고 가짜 저장소에 이미 호출 기록이 있어 더 싸고 결정적이다.
- **`ScimUseCaseConfig` 는 바뀌지 않는다** — 새 저장소 빈은 `DynamoDbConfig`, 새 SCIM 빈은 `ScimConfig` 에 둔다(기존 핸들러 빈이 있는 자리).
- **TTL 은 테이블을 만들 때만 켠다** — GSI1 키가 바뀌어 기존 테이블은 어차피 재생성해야 한다(스펙 §5.4).

## Review Focus

1. **대소문자만 다른 `userName` 두 명이 이미 있는 상태**(GSI 최종 일관성 창이나 이전 데이터) — `userName eq` 가 둘 다 돌려줘야 하고, 하나만 고르거나 오류가 나면 안 된다. Task 6 이 테스트한다.
2. **이어 읽을 위치의 아이템이 페이지 사이에 지워짐** — DynamoDB 의 `ExclusiveStartKey` 는 없는 키여도 그 자리부터 이어간다. 끊기거나 처음으로 돌아가면 안 된다. Task 2 가 테스트한다.
3. **`startIndex` 가 전체 수보다 큼** — 200 과 `totalResults` 는 그대로, `Resources: []`. Task 6 이 테스트한다.
4. **한글 값·이스케이프된 따옴표가 든 필터**(`userName eq "홍길동"`, `"a\"b"`) — 파서가 값을 그대로 돌려줘야 한다. Task 4 가 테스트한다.
5. **`excludedAttributes=MEMBERS` 처럼 대소문자가 다른 속성 이름** — RFC 는 속성 이름을 대소문자 무시로 다룬다. 멤버를 읽지 않아야 한다. Task 5 가 테스트한다.

## File Structure

| 파일 | 과제 | 책임 |
|---|---|---|
| `storage-dynamodb/.../storage/Keys.java` | 1·2·3 | `indexKey`, GSI3 상수, `PAGE#` 키, `EXPIRES_AT` |
| `storage-dynamodb/.../storage/DynamoDbDirectoryStateRepository.java` | 1·2 | GSI1SK 소문자, `toUser`/`toGroupHeader` 를 패키지 static 으로 |
| `storage-dynamodb/.../storage/DynamoDbDirectorySearchRepository.java` | 1 | GSI1 접두사 소문자 |
| `core/src/testFixtures/.../core/fake/FakeStateRepository.java` | 1 | `findUserIdsByUserName` 대소문자 무시 |
| `core/src/main/.../core/port/DirectoryQueryRepository.java` (신규) | 2 | 완전 일치·위치 읽기 포트 |
| `storage-dynamodb/.../storage/DynamoDbDirectoryQueryRepository.java` (신규) | 2 | 그 구현 |
| `storage-dynamodb/.../storage/TableInitializer.java` | 2·3 | GSI3, TTL |
| `storage-dynamodb/.../storage/DynamoDbConfig.java` | 2·3 | 빈 둘 |
| `core/src/main/.../core/query/ListingKind.java`, `PageBookmark.java` (신규) | 3 | 책갈피 타입 |
| `core/src/main/.../core/port/PageBookmarkRepository.java` (신규) | 3 | 책갈피 포트 |
| `storage-dynamodb/.../storage/DynamoDbPageBookmarkRepository.java` (신규) | 3 | 그 구현 |
| `connector-scim/.../scim/ScimResourceType.java`, `ScimFilter.java` (신규) | 4 | 리소스 설명, 필터 파서 |
| `connector-scim/.../scim/ScimException.java` | 4 | `invalidFilter`·`invalidValue`·`notImplemented` |
| `connector-scim/.../scim/ScimJson.java`, `ScimText.java`, `ScimAttributeProjection.java`, `ScimQuery.java`, `dto/ScimSearchRequest.java` (신규) | 5 | JSON 변환, 소문자 비교, 속성 선택, 조회 파라미터 |
| `connector-scim/.../scim/ScimSchemas.java` | 5 | ListResponse·SearchRequest URN |
| `core/src/testFixtures/.../core/fake/FakeQueryRepository.java`, `FakePageBookmarkRepository.java` (신규) | 6 | 가짜 |
| `connector-scim/.../scim/ScimPager.java`, `ScimUserListing.java`, `ScimGroupListing.java`, `dto/ScimListResponse.java` (신규) | 6 | 조회 엔진 |
| `connector-scim/.../scim/ScimMapper.java` | 6 | `toScimGroup(GroupHeader)` |
| `connector-scim/.../scim/ScimListHandler.java` (신규), `ScimRouter.java`, `ScimUserHandler.java`, `ScimGroupHandler.java`, `ScimConfig.java` | 7 | HTTP |
| `app-scim/src/test/.../ScimQueryEndToEndTest.java` (신규), `README.md` | 8 | E2E, 안내 |
| `app-scim/src/test/.../DynamoDbReadCounter.java`, `ScimListingScaleTest.java` (신규), 스펙 §11 | 9 | 10만 명 규모 |

(`...` = `src/main/java/dev/starryeye/organization`, 테스트는 `src/test/java/dev/starryeye/organization` 아래 같은 패키지.)

---

### Task 1: GSI1 정렬키를 소문자로

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`saveUser` GSI1SK, `saveGroup` GSI1SK, `findUserIdsByUserName`)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java` (`searchUsersByUserName`, `searchGroupsByDisplayName`)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` (`findUserIdsByUserName`)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`, `DynamoDbDirectorySearchRepositoryTest.java`

**Interfaces:**
- Produces: `public static String Keys.indexKey(String raw)` — `raw.toLowerCase(Locale.ROOT)`. GSI1 정렬키를 쓰고 묻는 모든 곳이 거친다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest` 에 추가(필드 이름은 `repository`):

```java
    @Test
    @DisplayName("userName 은 대소문자를 가리지 않고 찾는다 — 저장된 값은 보낸 그대로다")
    void userName_은_대소문자를_가리지_않는다() {
        // given
        repository.saveUser(new DirectoryUser("Kim", "e1", "Kim", "김철수", null, true)).block();

        // when
        List<String> ids = repository.findUserIdsByUserName("KIM").collectList().block();

        // then
        assertThat(ids).containsExactly("Kim");
        assertThat(repository.findUser("Kim").block().userName()).isEqualTo("Kim");
    }
```

`DynamoDbDirectorySearchRepositoryTest` 에 추가:

```java
    @Test
    @DisplayName("계정명·조직명 접두사 검색은 대소문자를 가리지 않는다 — 결과의 값은 보낸 그대로다")
    void 접두사_검색은_대소문자를_가리지_않는다() {
        // given
        state.saveUser(new DirectoryUser("gd.hong", "e1", "GD.Hong", "홍길동", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "g1", "Dev Team", Set.of())).block();

        // when
        var users = search.searchUsersByUserName("gd.h", null, 20).block();
        var groups = search.searchGroupsByDisplayName("DEV", null, 20).block();

        // then
        assertThat(users.items()).extracting(UserSummary::userName).containsExactly("GD.Hong");
        assertThat(groups.items()).extracting("displayName").containsExactly("Dev Team");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest' --tests '*DynamoDbDirectorySearchRepositoryTest'`
Expected: 새 두 테스트가 FAIL (`ids` 가 비어 있음, 검색 결과가 비어 있음)

- [ ] **Step 3: 구현한다**

`Keys.java` — `import java.util.Locale;` 를 더하고 `sortableTimestamp` 앞에 추가:

```java
    /**
     * GSI1 정렬키에 넣는 값. {@code userName}·조직 {@code displayName} 은 RFC 7643 에서
     * {@code caseExact=false} 라 대소문자를 가리지 않고 찾아야 한다 — 키만 소문자로 두고 속성은
     * 보낸 그대로 둔다(S-1 설계 §5.1). 쓰는 쪽과 묻는 쪽이 반드시 이 한 메서드를 거친다.
     */
    public static String indexKey(String raw) {
        return raw.toLowerCase(Locale.ROOT);
    }
```

`DynamoDbDirectoryStateRepository.saveUser`:

```java
        item.put(Keys.GSI1SK, Attrs.s(Keys.indexKey(user.userName() == null ? user.id() : user.userName())));
```

`DynamoDbDirectoryStateRepository.saveGroup`:

```java
        meta.put(Keys.GSI1SK, Attrs.s(Keys.indexKey(group.displayName() == null ? group.id() : group.displayName())));
```

`DynamoDbDirectoryStateRepository.findUserIdsByUserName` — 자바독 첫 문단 끝에 "정렬키는 소문자다({@link Keys#indexKey}) — {@code Kim} 이 있으면 {@code kim} 으로도 찾힌다." 를 더하고 값을 바꾼다:

```java
                        ":pk", Attrs.s(Keys.USER_INDEX), ":sk", Attrs.s(Keys.indexKey(userName))))
```

`DynamoDbDirectorySearchRepository` — GSI1 을 쓰는 두 검색만 접두사를 소문자로 묻는다(GSI2 표시명 검색은 원문 `displayName` 속성이 키라 그대로 둔다):

```java
    @Override
    public Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit) {
        // GSI1 정렬키는 소문자다(Keys.indexKey) — 접두사도 소문자로 묻는다
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.USER_INDEX,
                Keys.indexKey(prefix), cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }
```

```java
    @Override
    public Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit) {
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.GROUP_INDEX,
                Keys.indexKey(prefix), cursor, limit, DynamoDbDirectorySearchRepository::toGroupSummary);
    }
```

`FakeStateRepository.findUserIdsByUserName` — 실제 저장소와 같은 규칙으로 맞춘다(`import java.util.Locale;`):

```java
    @Override
    public Flux<String> findUserIdsByUserName(String userName) {
        if (userName == null) {
            return Flux.empty();
        }
        // 실제 저장소처럼 대소문자를 가리지 않는다(RFC 7643 userName caseExact=false)
        String key = userName.toLowerCase(Locale.ROOT);
        return Flux.fromIterable(users.values())
                .filter(user -> user.userName() != null && key.equals(user.userName().toLowerCase(Locale.ROOT)))
                .map(DirectoryUser::id);
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test :connector-scim:test`
Expected: 전부 PASS (connector-scim 은 가짜 저장소 변경의 영향 확인)

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add storage-dynamodb core/src/testFixtures
git commit -m "feat: userName·조직명 GSI1 키를 소문자로 — 대소문자를 가리지 않고 찾는다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 2: externalId GSI3 와 완전 일치·위치 읽기 포트

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryQueryRepository.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (GSI3 상수)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/TableInitializer.java` (`createTable` 에 GSI3)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`toUser` static, `toGroupHeader` 추가)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java` (빈)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepositoryTest.java` (신규), `TableInitializerTest.java`

**Interfaces:**
- Consumes: `Keys.indexKey(String)` (Task 1), `core.query.Page<T>(List<T> items, String nextCursor)`, `Cursor.encode/decode`, `Paginator.queryAll`
- Produces (`dev.starryeye.organization.core.port.DirectoryQueryRepository`):
  - `Flux<DirectoryUser> findUsersByUserName(String userName)`
  - `Flux<DirectoryUser> findUsersByExternalId(String externalId)`
  - `Flux<GroupHeader> findGroupHeadersByDisplayName(String displayName)`
  - `Flux<GroupHeader> findGroupHeadersByExternalId(String externalId)`
  - `Mono<Page<DirectoryUser>> listUsers(String from, int limit, boolean descending)`
  - `Mono<Page<GroupHeader>> listGroupHeaders(String from, int limit, boolean descending)`
  - `Mono<Long> countUsers()`, `Mono<Long> countGroups()`
  - `Mono<String> skipUsers(long n, boolean descending)`, `Mono<String> skipGroups(long n, boolean descending)` — 빈 Mono = 처음
- Produces: `Keys.GSI3 = "GSI3"`, `Keys.GSI3PK = "externalId"`, `Keys.GSI3SK = Keys.PK`
- Produces: 패키지 전용 `static DirectoryUser DynamoDbDirectoryStateRepository.toUser(String userId, Map<String, AttributeValue> item)`, `static GroupHeader toGroupHeader(String groupId, Map<String, AttributeValue> item)`

- [ ] **Step 1: 포트를 만든다** (컴파일만 되게 — 테스트는 Step 2)

`core/src/main/java/dev/starryeye/organization/core/port/DirectoryQueryRepository.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.query.Page;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SCIM 목록·필터 조회 전용 읽기 포트 (S-1 설계 §5.3).
 *
 * <p>{@link DirectorySearchRepository}(admin 용 "접두사 + 커서")와 계약이 달라 따로 둔다 — 이쪽은
 * <b>완전 일치</b>와 <b>인덱스 순서의 위치</b>다. 쓰기 경로의 심장인 {@link DirectoryStateRepository} 에
 * 조회를 얹지 않는다는 원칙도 같다.
 *
 * <p>{@code userName}·{@code displayName} 일치는 대소문자를 가리지 않는다(RFC 7643 {@code caseExact=false}).
 * {@code externalId} 는 가린다.
 *
 * <p>위치({@code from}, {@link Page#nextCursor()}, {@code skip*} 의 결과)는 저장소가 만든 불투명 문자열이다.
 * {@code from} 이 null 이면, 그리고 {@code skip*} 이 빈 {@link Mono} 면 "처음" 이다.
 */
public interface DirectoryQueryRepository {

    Flux<DirectoryUser> findUsersByUserName(String userName);

    Flux<DirectoryUser> findUsersByExternalId(String externalId);

    Flux<GroupHeader> findGroupHeadersByDisplayName(String displayName);

    Flux<GroupHeader> findGroupHeadersByExternalId(String externalId);

    /** {@code userName} 소문자 순(내림차순이면 역순)으로 {@code from} 다음부터 {@code limit} 건. */
    Mono<Page<DirectoryUser>> listUsers(String from, int limit, boolean descending);

    /** 조직명 소문자 순(내림차순이면 역순)으로 {@code from} 다음부터 {@code limit} 건. 멤버는 담지 않는다. */
    Mono<Page<GroupHeader>> listGroupHeaders(String from, int limit, boolean descending);

    Mono<Long> countUsers();

    Mono<Long> countGroups();

    /** 앞의 {@code n} 건을 건너뛴 위치. {@code n} 이 0 이하이거나 아무도 없으면 빈 Mono. 전원보다 많으면 끝 위치. */
    Mono<String> skipUsers(long n, boolean descending);

    Mono<String> skipGroups(long n, boolean descending);
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepositoryTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.query.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbDirectoryQueryRepositoryTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository state;
    private DynamoDbDirectoryQueryRepository query;

    @BeforeEach
    void 저장소를_준비한다() {
        state = new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC());
        query = new DynamoDbDirectoryQueryRepository(client, properties, state);
    }

    private void 직원(String id, String userName, String externalId) {
        state.saveUser(new DirectoryUser(id, externalId, userName, "직원 " + id, null, true)).block();
    }

    private void 조직(String id, String displayName, String externalId) {
        state.saveGroup(new DirectoryGroup(id, externalId, displayName, Set.of())).block();
    }

    /** 대소문자가 섞인 일곱 명. 소문자 순서는 A b c D e F g 다. */
    private void 일곱_명을_둔다() {
        for (String name : List.of("b", "A", "c", "D", "e", "F", "g")) {
            직원(name, name, "ext-" + name);
        }
    }

    private List<String> 끝까지_읽는다(int limit, boolean descending) {
        List<String> seen = new ArrayList<>();
        String from = null;
        do {
            Page<DirectoryUser> page = query.listUsers(from, limit, descending).block();
            page.items().forEach(user -> seen.add(user.id()));
            from = page.nextCursor();
        } while (from != null);
        return seen;
    }

    @Test
    @DisplayName("userName 이 같으면 대소문자가 달라도 찾는다 — 돌려주는 값은 저장된 그대로다")
    void userName_일치는_대소문자를_가리지_않는다() {
        // given
        직원("Kim.Lee", "Kim.Lee", "ext-kim");
        직원("park", "park", "ext-park");

        // when
        List<DirectoryUser> found = query.findUsersByUserName("kIM.lEE").collectList().block();

        // then
        assertThat(found).extracting(DirectoryUser::userName).containsExactly("Kim.Lee");
    }

    @Test
    @DisplayName("조직명이 같으면 대소문자가 달라도 찾는다")
    void 조직명_일치는_대소문자를_가리지_않는다() {
        // given
        조직("DEV001", "Dev Team", "grp-dev");

        // when
        List<GroupHeader> found = query.findGroupHeadersByDisplayName("DEV TEAM").collectList().block();

        // then
        assertThat(found).containsExactly(new GroupHeader("DEV001", "grp-dev", "Dev Team"));
    }

    @Test
    @DisplayName("externalId 가 같은 직원과 조직을 종류별로 가른다")
    void externalId_는_종류별로_가른다() {
        // given — GSI3 는 직원 아이템과 조직 META 를 한 인덱스에 싣는다
        직원("u1", "u1", "X-1");
        조직("G1", "조직 1", "X-1");

        // when
        List<DirectoryUser> users = query.findUsersByExternalId("X-1").collectList().block();
        List<GroupHeader> groups = query.findGroupHeadersByExternalId("X-1").collectList().block();

        // then
        assertThat(users).extracting(DirectoryUser::id).containsExactly("u1");
        assertThat(groups).extracting(GroupHeader::id).containsExactly("G1");
    }

    @Test
    @DisplayName("externalId 는 대소문자를 가린다")
    void externalId_는_대소문자를_가린다() {
        // given
        직원("u1", "u1", "X-1");

        // when
        List<DirectoryUser> found = query.findUsersByExternalId("x-1").collectList().block();

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("externalId 로 찾은 직원은 본 테이블의 최신 값이다")
    void externalId_로_찾으면_최신_값이다() {
        // given
        state.saveUser(new DirectoryUser("u1", "X-1", "u1", "옛 이름", null, true)).block();
        state.saveUser(new DirectoryUser("u1", "X-1", "u1", "새 이름", null, false)).block();

        // when
        DirectoryUser found = query.findUsersByExternalId("X-1").blockFirst();

        // then
        assertThat(found.displayName()).isEqualTo("새 이름");
        assertThat(found.active()).isFalse();
    }

    @Test
    @DisplayName("오름차순으로 끝까지 이어 읽으면 전원이 userName 소문자 순으로 한 번씩 나오고 조직은 섞이지 않는다")
    void 오름차순으로_끝까지_읽는다() {
        // given
        일곱_명을_둔다();
        조직("G1", "a 조직", "grp-1");

        // when
        List<String> seen = 끝까지_읽는다(3, false);

        // then
        assertThat(seen).containsExactly("A", "b", "c", "D", "e", "F", "g");
    }

    @Test
    @DisplayName("내림차순으로 끝까지 이어 읽으면 역순으로 한 번씩 나온다")
    void 내림차순으로_끝까지_읽는다() {
        // given
        일곱_명을_둔다();

        // when
        List<String> seen = 끝까지_읽는다(3, true);

        // then
        assertThat(seen).containsExactly("g", "F", "e", "D", "c", "b", "A");
    }

    @Test
    @DisplayName("앞의 n 건을 건너뛴 위치에서 이어 읽으면 순서대로 읽은 것과 같다")
    void 건너뛴_위치는_순서대로_읽은_위치와_같다() {
        // given
        일곱_명을_둔다();

        // when
        String position = query.skipUsers(3, false).block();
        Page<DirectoryUser> page = query.listUsers(position, 2, false).block();

        // then
        assertThat(page.items()).extracting(DirectoryUser::id).containsExactly("D", "e");
        assertThat(query.skipUsers(0, false).blockOptional()).isEmpty();
        assertThat(query.listUsers(query.skipUsers(100, false).block(), 2, false).block().items()).isEmpty();
    }

    @Test
    @DisplayName("직원 수와 조직 수를 따로 센다")
    void 직원과_조직을_따로_센다() {
        // given
        일곱_명을_둔다();
        조직("G1", "조직 1", "grp-1");
        조직("G2", "조직 2", "grp-2");

        // when, then
        assertThat(query.countUsers().block()).isEqualTo(7L);
        assertThat(query.countGroups().block()).isEqualTo(2L);
    }

    @Test
    @DisplayName("조직 목록도 조직명 소문자 순으로 이어 읽는다")
    void 조직_목록을_이어_읽는다() {
        // given
        조직("G1", "beta", "grp-1");
        조직("G2", "Alpha", "grp-2");
        조직("G3", "gamma", "grp-3");

        // when
        Page<GroupHeader> first = query.listGroupHeaders(null, 2, false).block();
        Page<GroupHeader> second = query.listGroupHeaders(first.nextCursor(), 2, false).block();

        // then
        assertThat(first.items()).extracting(GroupHeader::id).containsExactly("G2", "G1");
        assertThat(second.items()).extracting(GroupHeader::id).containsExactly("G3");
    }

    @Test
    @DisplayName("이어 읽을 위치의 직원이 그사이 지워져도 그 다음부터 이어 읽는다")
    void 이어_읽을_위치가_지워져도_이어_읽는다() {
        // given — 첫 페이지의 마지막 직원(c)을 페이지 사이에 지운다
        일곱_명을_둔다();
        Page<DirectoryUser> first = query.listUsers(null, 3, false).block();
        state.deleteUser("c").block();

        // when
        Page<DirectoryUser> second = query.listUsers(first.nextCursor(), 3, false).block();

        // then
        assertThat(second.items()).extracting(DirectoryUser::id).containsExactly("D", "e", "F");
    }
}
```

`TableInitializerTest` 에 추가(파일의 기존 import 방식과 필드를 따른다. `DescribeTableRequest`, `ProjectionType` import):

```java
    @Test
    @DisplayName("externalId 로 찾는 GSI3 를 키만 담아 만든다")
    void GSI3_를_만든다() {
        // when
        var table = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();

        // then
        var gsi3 = table.globalSecondaryIndexes().stream()
                .filter(index -> Keys.GSI3.equals(index.indexName()))
                .findFirst().orElseThrow();
        assertThat(gsi3.keySchema()).extracting(k -> k.attributeName()).containsExactly("externalId", "PK");
        assertThat(gsi3.projection().projectionType()).isEqualTo(ProjectionType.KEYS_ONLY);
    }
```

(`TableInitializerTest` 가 `DynamoDbTestSupport` 를 상속하지 않으면, 그 파일이 테이블을 만드는 방식대로 `ensureTable()` 뒤에 describe 한다.)

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryQueryRepositoryTest' --tests '*TableInitializerTest'`
Expected: 컴파일 실패(`DynamoDbDirectoryQueryRepository` 없음)

- [ ] **Step 4: 구현한다**

`Keys.java` — `GSI2SK` 선언 뒤에 추가:

```java
    /**
     * GSI3 — {@code externalId} 로 직원·조직을 찾는다(S-1 설계 §5.2). GSI2 처럼 <b>새 속성을 만들지
     * 않는다</b> — 파티션키는 아이템이 이미 가진 {@code externalId} 속성, 정렬키는 본 테이블의 {@link #PK} 다.
     * {@code externalId} 가 없는 아이템(멤버 줄·소속 줄·스냅샷)은 인덱스에 실리지 않는다.
     */
    public static final String GSI3 = "GSI3";
    public static final String GSI3PK = "externalId";
    /** @see #GSI3 — 본 테이블의 파티션키 속성 그 자체다. {@code USER#}/{@code GROUP#} 접두사로 종류를 가른다. */
    public static final String GSI3SK = PK;
```

`TableInitializer.createTable` — 속성 정의에 `attribute(Keys.GSI3PK)` 를 더하고(`GSI3SK` 는 `PK` 라 다시 적지 않는다), 인덱스 목록에 `externalIdIndex()` 를 더한다:

```java
                .attributeDefinitions(
                        attribute(Keys.PK), attribute(Keys.SK),
                        attribute(Keys.GSI1PK), attribute(Keys.GSI1SK),
                        attribute(Keys.GSI2SK), attribute(Keys.GSI3PK))
```

```java
                        userDisplayNameIndex(),
                        externalIdIndex())
```

그리고 `userDisplayNameIndex()` 뒤에:

```java
    /**
     * {@code externalId} 로 찾는 인덱스. {@code KEYS_ONLY} 인 이유 — 찾은 {@code PK} 로 본 테이블을 GetItem 해
     * 최신 값을 읽는다. 인덱스가 늦어도 낡은 속성을 돌려주지 않고, 인덱스가 작다(S-1 설계 §5.2).
     *
     * <p>기존 테이블에 없으면 더하는 경로({@link #addMissingIndex})는 두지 않는다 — S-1 은 GSI1 키 값도 바꾸므로
     * 기존 테이블은 어차피 재생성해야 한다(설계 §5.4).
     */
    private static GlobalSecondaryIndex externalIdIndex() {
        return GlobalSecondaryIndex.builder()
                .indexName(Keys.GSI3)
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.GSI3PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.GSI3SK).keyType(KeyType.RANGE).build())
                .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                .build();
    }
```

`DynamoDbDirectoryStateRepository` — `toUser` 를 패키지 전용 static 으로 바꾸고(본문 그대로), `toGroupHeader` 를 더해 `findGroupHeader` 가 쓰게 한다:

```java
    /** 직원 META 아이템을 읽는다. GSI1(ALL 프로젝션) 아이템도 같은 속성을 가져 조회 저장소가 함께 쓴다. */
    static DirectoryUser toUser(String userId, Map<String, AttributeValue> item) {
        return new DirectoryUser(
                userId,
                Attrs.str(item, EXTERNAL_ID),
                Attrs.str(item, USER_NAME),
                Attrs.str(item, DISPLAY_NAME),
                Attrs.str(item, EMAIL),
                Attrs.flag(item, ACTIVE));
    }

    /** 조직 META 아이템을 읽는다. 조회 저장소가 함께 쓴다. */
    static GroupHeader toGroupHeader(String groupId, Map<String, AttributeValue> item) {
        return new GroupHeader(groupId, Attrs.str(item, EXTERNAL_ID), Attrs.str(item, DISPLAY_NAME));
    }
```

```java
                .filter(GetItemResponse::hasItem)
                .map(response -> toGroupHeader(groupId, response.item()));
```

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.query.Page;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;

import java.util.Map;
import java.util.function.Function;

/**
 * SCIM 목록·필터 조회의 읽기 (S-1 설계 §5.3).
 *
 * <p>{@code userName}·조직명은 GSI1 의 소문자 정렬키({@link Keys#indexKey})로, {@code externalId} 는 GSI3 로
 * 찾는다. GSI3 는 키만 담으므로 찾은 {@code PK} 로 본 테이블을 강한 일관성으로 다시 읽는다 — 인덱스가 늦어도
 * 낡은 속성을 돌려주지 않는다.
 *
 * <p>목록은 GSI1 파티션({@code USER_INDEX}/{@code GROUP_INDEX})을 정렬키 순서로 {@code limit} 건씩 읽는다.
 * 위치는 {@link Cursor} 로 감싼 LastEvaluatedKey 다. 파티션 전체를 읽는 것은 {@link #countUsers}·
 * {@link #skipUsers} 뿐이고, 그 둘은 책갈피가 없을 때만 불린다(설계 §4.4).
 */
@RequiredArgsConstructor
public class DynamoDbDirectoryQueryRepository implements DirectoryQueryRepository {

    /** 커서에 담는 검색 범위. admin 검색의 커서가 이 목록에 흘러들면 {@link Cursor#decode} 가 거절한다. */
    private static final String USERS_SCOPE = "SCIM/" + Keys.GSI1 + "/" + Keys.USER_INDEX;
    private static final String GROUPS_SCOPE = "SCIM/" + Keys.GSI1 + "/" + Keys.GROUP_INDEX;

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    /** GSI3 로 찾은 키를 본 테이블에서 강한 일관성으로 다시 읽는다. */
    private final DirectoryStateRepository state;

    @Override
    public Flux<DirectoryUser> findUsersByUserName(String userName) {
        return exact(Keys.USER_INDEX, userName).map(DynamoDbDirectoryQueryRepository::user);
    }

    @Override
    public Flux<DirectoryUser> findUsersByExternalId(String externalId) {
        return byExternalId(externalId, Keys.USER_PREFIX)
                .concatMap(pk -> state.findUser(Keys.parseUserPk(pk)));
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByDisplayName(String displayName) {
        return exact(Keys.GROUP_INDEX, displayName).map(DynamoDbDirectoryQueryRepository::group);
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByExternalId(String externalId) {
        return byExternalId(externalId, Keys.GROUP_PREFIX)
                .concatMap(pk -> state.findGroupHeader(Keys.parseGroupPk(pk)));
    }

    @Override
    public Mono<Page<DirectoryUser>> listUsers(String from, int limit, boolean descending) {
        return page(USERS_SCOPE, Keys.USER_INDEX, from, limit, descending, DynamoDbDirectoryQueryRepository::user);
    }

    @Override
    public Mono<Page<GroupHeader>> listGroupHeaders(String from, int limit, boolean descending) {
        return page(GROUPS_SCOPE, Keys.GROUP_INDEX, from, limit, descending, DynamoDbDirectoryQueryRepository::group);
    }

    @Override
    public Mono<Long> countUsers() {
        return count(Keys.USER_INDEX);
    }

    @Override
    public Mono<Long> countGroups() {
        return count(Keys.GROUP_INDEX);
    }

    @Override
    public Mono<String> skipUsers(long n, boolean descending) {
        return skip(USERS_SCOPE, Keys.USER_INDEX, n, descending);
    }

    @Override
    public Mono<String> skipGroups(long n, boolean descending) {
        return skip(GROUPS_SCOPE, Keys.GROUP_INDEX, n, descending);
    }

    private static DirectoryUser user(Map<String, AttributeValue> item) {
        return DynamoDbDirectoryStateRepository.toUser(Keys.parseUserPk(Attrs.str(item, Keys.PK)), item);
    }

    private static GroupHeader group(Map<String, AttributeValue> item) {
        return DynamoDbDirectoryStateRepository.toGroupHeader(Keys.parseGroupPk(Attrs.str(item, Keys.PK)), item);
    }

    /** GSI1 정렬키가 값의 소문자와 같은 아이템. 대소문자만 다른 둘이 있으면 둘 다 돌려준다. */
    private Flux<Map<String, AttributeValue>> exact(String partition, String value) {
        if (value == null || value.isEmpty()) {
            return Flux.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk AND #sk = :sk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK, "#sk", Keys.GSI1SK))
                .expressionAttributeValues(Map.of(
                        ":pk", Attrs.s(partition), ":sk", Attrs.s(Keys.indexKey(value))))
                .build();
        return Paginator.queryAll(client, request);
    }

    /** GSI3 에서 {@code externalId} 가 같은 아이템 중 {@code prefix} 종류의 META 만 골라 PK 를 준다. */
    private Flux<String> byExternalId(String externalId, String prefix) {
        if (externalId == null || externalId.isEmpty()) {
            return Flux.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI3)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI3PK, "#sk", Keys.GSI3SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(externalId), ":prefix", Attrs.s(prefix)))
                .build();
        return Paginator.queryAll(client, request)
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .map(item -> Attrs.str(item, Keys.PK));
    }

    /**
     * {@code Mono.defer} 인 이유는 admin 검색과 같다 — {@link Cursor#decode} 가 손상된 위치에서 던지는 예외를
     * 조립 시점이 아니라 구독 시점의 {@code onError} 로 만든다.
     */
    private <T> Mono<Page<T>> page(String scope, String partition, String from, int limit, boolean descending,
                                   Function<Map<String, AttributeValue>, T> mapper) {
        return Mono.defer(() -> {
            QueryRequest.Builder request = partitionQuery(partition, descending).limit(limit);
            Map<String, AttributeValue> start = Cursor.decode(scope, from);
            if (start != null) {
                request.exclusiveStartKey(start);
            }
            return Mono.fromFuture(() -> client.query(request.build()))
                    .map(response -> new Page<>(
                            response.items().stream().map(mapper).toList(),
                            Cursor.encode(scope, response.lastEvaluatedKey())));
        });
    }

    private QueryRequest.Builder partitionQuery(String partition, boolean descending) {
        return QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(partition)))
                .scanIndexForward(!descending);
    }

    /** 파티션을 한 번 훑어 센다. 1MB 마다 끊기므로 LastEvaluatedKey 를 끝까지 따라간다. */
    private Mono<Long> count(String partition) {
        QueryRequest request = partitionQuery(partition, false).select(Select.COUNT).build();
        return Mono.fromFuture(() -> client.query(request))
                .expand(response -> {
                    Map<String, AttributeValue> lastKey = response.lastEvaluatedKey();
                    if (lastKey == null || lastKey.isEmpty()) {
                        return Mono.empty();
                    }
                    QueryRequest next = request.toBuilder().exclusiveStartKey(lastKey).build();
                    return Mono.fromFuture(() -> client.query(next));
                })
                .map(QueryResponse::count)
                .reduce(0L, (sum, count) -> sum + count);
    }

    /**
     * 앞의 {@code n} 건을 키만 읽으며 건너뛰고, 마지막으로 건너뛴 아이템의 키를 위치로 준다. 그 키가 곧 GSI1
     * 질의의 LastEvaluatedKey 모양({@code PK, SK, GSI1PK, GSI1SK})이다. 책갈피가 없을 때만 불린다.
     */
    private Mono<String> skip(String scope, String partition, long n, boolean descending) {
        if (n <= 0) {
            return Mono.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#gpk = :pk")
                .projectionExpression("#pk, #sk, #gpk, #gsk")
                .expressionAttributeNames(Map.of(
                        "#pk", Keys.PK, "#sk", Keys.SK, "#gpk", Keys.GSI1PK, "#gsk", Keys.GSI1SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(partition)))
                .scanIndexForward(!descending)
                .build();
        return Paginator.queryAll(client, request)
                .take(n)
                .reduce((previous, current) -> current)
                .map(last -> Cursor.encode(scope, last));
    }
}
```

`DynamoDbConfig` — `directorySearchRepository` 빈 뒤에(`import dev.starryeye.organization.core.port.DirectoryQueryRepository;`):

```java
    @Bean
    public DirectoryQueryRepository directoryQueryRepository(DynamoDbAsyncClient client,
                                                             DynamoDbProperties properties,
                                                             DynamoDbDirectoryStateRepository state) {
        return new DynamoDbDirectoryQueryRepository(client, properties, state);
    }
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test`
Expected: 전부 PASS

- [ ] **Step 6: 커밋하고 푸시한다**

```bash
git add core/src/main storage-dynamodb
git commit -m "feat: externalId GSI3 와 SCIM 조회용 완전 일치·위치 읽기 저장소" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 3: 페이지 책갈피 저장소

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/query/ListingKind.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/query/PageBookmark.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/PageBookmarkRepository.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbPageBookmarkRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (`PAGE_PREFIX`, `pagePk`, `pageSk`, `EXPIRES_AT`)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/TableInitializer.java` (생성 후 TTL)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java` (빈)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbPageBookmarkRepositoryTest.java` (신규), `TableInitializerTest.java`

**Interfaces:**
- Produces: `enum ListingKind { USER, GROUP }`, `record PageBookmark(String position, long totalResults)`
- Produces (`PageBookmarkRepository`): `Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex)`, `Mono<Void> save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark)`
- Produces: `Keys.EXPIRES_AT = "expiresAt"`, `DynamoDbPageBookmarkRepository.TTL = Duration.ofMinutes(15)`

- [ ] **Step 1: 타입과 포트를 만든다**

`core/src/main/java/dev/starryeye/organization/core/query/ListingKind.java`:

```java
package dev.starryeye.organization.core.query;

/** SCIM 목록의 종류. 책갈피를 종류별로 가른다. */
public enum ListingKind {
    USER,
    GROUP
}
```

`core/src/main/java/dev/starryeye/organization/core/query/PageBookmark.java`:

```java
package dev.starryeye.organization.core.query;

/**
 * 필터 없는 SCIM 목록의 다음 페이지를 이어 읽을 자리 (S-1 설계 §4.4).
 *
 * @param position     저장소가 만든 불투명 위치 — {@code DirectoryQueryRepository.listUsers} 의 {@code from}
 * @param totalResults 이 가져오기의 첫 페이지에서 센 전체 수. 이어지는 페이지가 그대로 쓴다
 */
public record PageBookmark(String position, long totalResults) {
}
```

`core/src/main/java/dev/starryeye/organization/core/port/PageBookmarkRepository.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import reactor.core.publisher.Mono;

/**
 * SCIM 목록 페이지의 책갈피 (S-1 설계 §5.3).
 *
 * <p>IdP 는 가져오기를 {@code startIndex=1, 101, 201…} 처럼 순서대로 부른다. 페이지를 줄 때 다음
 * {@code startIndex} 에서 이어 읽을 위치를 여기 두면, 다음 요청은 앞을 건너뛰지 않고 그 자리부터 읽는다.
 * 앱이 여러 대여도 이어지도록 공유 저장소에 둔다. 수명이 짧아(15분) 디렉터리 읽기와 포트를 나눈다.
 */
public interface PageBookmarkRepository {

    /** 없거나 만료됐으면 빈 Mono. */
    Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex);

    /** 같은 키에 이미 있으면 덮어쓴다 — 동시에 도는 두 가져오기의 책갈피는 둘 다 올바른 위치다. */
    Mono<Void> save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark);
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbPageBookmarkRepositoryTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbPageBookmarkRepositoryTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-09-25T00:00:00Z");

    private DynamoDbPageBookmarkRepository 시각(Instant at) {
        return new DynamoDbPageBookmarkRepository(client, properties, Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("저장한 책갈피를 읽는다")
    void 저장한_책갈피를_읽는다() {
        // given
        var bookmarks = 시각(지금);
        bookmarks.save(ListingKind.USER, false, 101, new PageBookmark("pos-101", 250)).block();

        // when
        PageBookmark found = bookmarks.find(ListingKind.USER, false, 101).block();

        // then
        assertThat(found).isEqualTo(new PageBookmark("pos-101", 250));
    }

    @Test
    @DisplayName("종류·방향·위치가 다르면 다른 책갈피다")
    void 종류_방향_위치가_다르면_다르다() {
        // given
        var bookmarks = 시각(지금);
        bookmarks.save(ListingKind.USER, false, 101, new PageBookmark("pos-101", 250)).block();

        // when, then
        assertThat(bookmarks.find(ListingKind.GROUP, false, 101).blockOptional()).isEmpty();
        assertThat(bookmarks.find(ListingKind.USER, true, 101).blockOptional()).isEmpty();
        assertThat(bookmarks.find(ListingKind.USER, false, 201).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("15분이 지난 책갈피는 아직 지워지지 않았어도 없는 것이다")
    void 만료된_책갈피는_없는_것이다() {
        // given — TTL 삭제는 늦게 일어나므로 읽는 쪽이 만료를 판단해야 한다
        시각(지금).save(ListingKind.USER, false, 101, new PageBookmark("pos-101", 250)).block();

        // when, then
        assertThat(시각(지금.plus(Duration.ofMinutes(15)).minusSeconds(1))
                .find(ListingKind.USER, false, 101).blockOptional()).isPresent();
        assertThat(시각(지금.plus(Duration.ofMinutes(15)))
                .find(ListingKind.USER, false, 101).blockOptional()).isEmpty();
    }
}
```

`TableInitializerTest` 에 추가(`DescribeTimeToLiveRequest`, `TimeToLiveStatus` import):

```java
    @Test
    @DisplayName("테이블을 만들 때 책갈피 만료(TTL)를 켠다")
    void TTL_을_켠다() {
        // when
        var ttl = client.describeTimeToLive(DescribeTimeToLiveRequest.builder()
                .tableName(properties.getTableName()).build()).join().timeToLiveDescription();

        // then
        assertThat(ttl.attributeName()).isEqualTo(Keys.EXPIRES_AT);
        assertThat(ttl.timeToLiveStatus()).isIn(TimeToLiveStatus.ENABLED, TimeToLiveStatus.ENABLING);
    }
```

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbPageBookmarkRepositoryTest' --tests '*TableInitializerTest'`
Expected: 컴파일 실패(`DynamoDbPageBookmarkRepository`, `Keys.EXPIRES_AT` 없음)

- [ ] **Step 4: 구현한다**

`Keys.java` — 접두사 상수들 뒤에 추가:

```java
    /** SCIM 목록 책갈피 파티션 접두사 (S-1 설계 §5.3). */
    public static final String PAGE_PREFIX = "PAGE#";

    /** 책갈피 만료 시각(epoch 초) 속성. 테이블 TTL 이 이 속성을 본다. */
    public static final String EXPIRES_AT = "expiresAt";
```

`sortableTimestamp` 앞에 추가:

```java
    /** 책갈피 파티션키 — 종류·방향별로 하나. 예: {@code PAGE#USER#ASC}. */
    public static String pagePk(String kind, boolean descending) {
        return PAGE_PREFIX + kind + (descending ? "#DESC" : "#ASC");
    }

    /** 책갈피 정렬키 — 이 책갈피로 이어 읽는 {@code startIndex}. 예: {@code START#101}. */
    public static String pageSk(long startIndex) {
        return "START#" + startIndex;
    }
```

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbPageBookmarkRepository.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * SCIM 목록 책갈피 (S-1 설계 §5.3). 아이템 하나가 책갈피 하나다 — PK {@code PAGE#<종류>#<방향>},
 * SK {@code START#<startIndex>}.
 *
 * <p>만료는 테이블 TTL({@link Keys#EXPIRES_AT})이 치우지만 그 삭제는 늦게 일어난다. 그래서 읽을 때 만료
 * 시각을 직접 보고, 지났으면 없는 것으로 본다.
 */
@RequiredArgsConstructor
public class DynamoDbPageBookmarkRepository implements PageBookmarkRepository {

    /** IdP 가 다음 페이지를 이 안에 부르지 않으면 처음부터 건너뛰어 읽는다. 느릴 뿐 틀리지 않는다. */
    static final Duration TTL = Duration.ofMinutes(15);

    private static final String POSITION = "position";
    private static final String TOTAL_RESULTS = "totalResults";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    /**
     * <b>강한 일관성으로 읽는다</b> — 바로 전 요청(다른 인스턴스일 수 있다)이 쓴 책갈피를 놓치면 건너뛰기로
     * 떨어진다. 틀리지는 않지만 페이지당 비용이 전원 수로 뛴다.
     */
    @Override
    public Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(key(kind, descending, startIndex))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(GetItemResponse::item)
                .filter(item -> Attrs.longValue(item, Keys.EXPIRES_AT) > clock.instant().getEpochSecond())
                .map(item -> new PageBookmark(Attrs.str(item, POSITION), Attrs.longValue(item, TOTAL_RESULTS)));
    }

    @Override
    public Mono<Void> save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark) {
        Map<String, AttributeValue> item = new HashMap<>(key(kind, descending, startIndex));
        item.put(POSITION, Attrs.s(bookmark.position()));
        item.put(TOTAL_RESULTS, Attrs.n(bookmark.totalResults()));
        item.put(Keys.EXPIRES_AT, Attrs.n(clock.instant().plus(TTL).getEpochSecond()));
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(properties.getTableName())
                        .item(item)
                        .build()))
                .then();
    }

    private static Map<String, AttributeValue> key(ListingKind kind, boolean descending, long startIndex) {
        return Map.of(
                Keys.PK, Attrs.s(Keys.pagePk(kind.name(), descending)),
                Keys.SK, Attrs.s(Keys.pageSk(startIndex)));
    }
}
```

`TableInitializer.createTable` — 마지막 `return` 을 바꾼다(`TimeToLiveSpecification`, `UpdateTimeToLiveRequest`, `DescribeTableRequest`, `software.amazon.awssdk.services.dynamodb.waiters.DynamoDbAsyncWaiter` import):

```java
        // TTL 은 테이블이 ACTIVE 가 된 뒤에만 켤 수 있다 — AWS 에서는 생성 직후 CREATING 이다
        return Mono.fromFuture(() -> client.createTable(request))
                .then(Mono.usingWhen(
                        Mono.fromSupplier(client::waiter),
                        waiter -> Mono.fromFuture(() -> waiter.waitUntilTableExists(
                                DescribeTableRequest.builder().tableName(table).build())),
                        waiter -> Mono.fromRunnable(waiter::close)))
                .then(Mono.fromFuture(() -> client.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(table)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .attributeName(Keys.EXPIRES_AT)
                                .enabled(true)
                                .build())
                        .build())))
                .then();
```

`DynamoDbConfig` — 빈 추가(`import dev.starryeye.organization.core.port.PageBookmarkRepository;`):

```java
    @Bean
    public PageBookmarkRepository pageBookmarkRepository(DynamoDbAsyncClient client,
                                                         DynamoDbProperties properties,
                                                         Clock clock) {
        return new DynamoDbPageBookmarkRepository(client, properties, clock);
    }
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test`
Expected: 전부 PASS

- [ ] **Step 6: 커밋하고 푸시한다**

```bash
git add core/src/main storage-dynamodb
git commit -m "feat: SCIM 목록 페이지 책갈피 저장소 — TTL 15분, 읽을 때 만료 판단" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 4: 필터 파서

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimResourceType.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimFilter.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimException.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimFilterTest.java` (신규)

**Interfaces:**
- Produces: `ScimException.invalidFilter(String)` (400 `invalidFilter`), `ScimException.invalidValue(String)` (400 `invalidValue`), `ScimException.notImplemented(String)` (501, scimType 없음)
- Produces: `enum ScimResourceType { USER, GROUP }` — `String schemaUrn()`, `String sortAttribute()`(`"userName"`/`"displayName"`), `Set<String> attributes()`(응답 속성 경로, 소문자), `String localName(String path)`(URN 을 떼고 소문자, 다른 스키마 URN 이면 null)
- Produces: `record ScimFilter(List<Term> terms)`, `record ScimFilter.Term(String attribute, Object value)` — `attribute` 는 URN 을 뗀 소문자 이름, `value` 는 `String` 또는 `Boolean`; `static ScimFilter parse(String raw, ScimResourceType type)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`connector-scim/src/test/java/dev/starryeye/organization/scim/ScimFilterTest.java`:

```java
package dev.starryeye.organization.scim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimFilterTest {

    private static ScimFilter 파싱(String filter) {
        return ScimFilter.parse(filter, ScimResourceType.USER);
    }

    private static void 거절한다(String filter) {
        assertThatThrownBy(() -> 파싱(filter))
                .as(filter)
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("invalidFilter");
                });
    }

    @Test
    @DisplayName("속성 eq 문자열 하나를 읽는다 — 속성 이름은 소문자로 돌려준다")
    void eq_하나를_읽는다() {
        // when
        ScimFilter filter = 파싱("userName eq \"kim\"");

        // then
        assertThat(filter.terms()).containsExactly(new ScimFilter.Term("username", "kim"));
    }

    @Test
    @DisplayName("연산자와 속성 이름은 대소문자를 가리지 않는다")
    void 연산자와_속성_이름은_대소문자를_가리지_않는다() {
        // when
        ScimFilter filter = 파싱("USERNAME EQ \"kim\" AND Active Eq TRUE");

        // then
        assertThat(filter.terms()).containsExactly(
                new ScimFilter.Term("username", "kim"),
                new ScimFilter.Term("active", Boolean.TRUE));
    }

    @Test
    @DisplayName("리소스의 core 스키마 URN 이 붙은 속성 이름을 받는다")
    void URN_이_붙은_이름을_받는다() {
        // when
        ScimFilter filter = 파싱("urn:ietf:params:scim:schemas:core:2.0:User:userName eq \"kim\"");

        // then
        assertThat(filter.terms()).containsExactly(new ScimFilter.Term("username", "kim"));
    }

    @Test
    @DisplayName("문자열의 이스케이프를 풀고 한글은 그대로 둔다")
    void 이스케이프를_풀고_한글은_그대로_둔다() {
        // when, then
        assertThat(파싱("userName eq \"a\\\"b\\\\c\\u0041\"").terms().get(0).value()).isEqualTo("a\"b\\cA");
        assertThat(파싱("displayName eq \"홍길동\"").terms().get(0).value()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("and 로 여러 조건을 잇는다")
    void and_로_잇는다() {
        // when
        ScimFilter filter = 파싱("userName eq \"kim\" and externalId eq \"e1\" and active eq false");

        // then
        assertThat(filter.terms()).containsExactly(
                new ScimFilter.Term("username", "kim"),
                new ScimFilter.Term("externalid", "e1"),
                new ScimFilter.Term("active", Boolean.FALSE));
    }

    @Test
    @DisplayName("or·not·괄호는 받지 않는다")
    void or_not_괄호는_받지_않는다() {
        거절한다("userName eq \"a\" or userName eq \"b\"");
        거절한다("not (userName eq \"a\")");
        거절한다("(userName eq \"a\")");
    }

    @Test
    @DisplayName("eq 가 아닌 연산자는 받지 않는다")
    void eq_가_아닌_연산자는_받지_않는다() {
        거절한다("userName ne \"a\"");
        거절한다("userName co \"a\"");
        거절한다("userName sw \"a\"");
        거절한다("userName pr");
        거절한다("meta.lastModified gt \"2026-01-01T00:00:00Z\"");
    }

    @Test
    @DisplayName("대괄호 값 경로와 하위 속성은 받지 않는다")
    void 값_경로와_하위_속성은_받지_않는다() {
        거절한다("emails[type eq \"work\"].value eq \"a@b.c\"");
        거절한다("name.familyName eq \"김\"");
    }

    @Test
    @DisplayName("문자열·true·false 가 아닌 값은 받지 않는다")
    void 문자열과_불리언이_아닌_값은_받지_않는다() {
        거절한다("userName eq kim");
        거절한다("userName eq 1");
        거절한다("userName eq null");
    }

    @Test
    @DisplayName("공백 두 칸, 끝나지 않은 and, 닫히지 않은 따옴표, 빈 필터는 받지 않는다")
    void 문법이_어긋나면_받지_않는다() {
        거절한다("userName  eq \"a\"");
        거절한다("userName eq \"a\" and");
        거절한다("userName eq \"a");
        거절한다("userName eq \"a\" ");
        거절한다("");
    }

    @Test
    @DisplayName("다른 리소스의 스키마 URN 이 붙은 이름은 받지 않는다")
    void 다른_스키마_URN_은_받지_않는다() {
        거절한다("urn:ietf:params:scim:schemas:core:2.0:Group:displayName eq \"a\"");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimFilterTest'`
Expected: 컴파일 실패(`ScimFilter`, `ScimResourceType` 없음)

- [ ] **Step 3: 구현한다**

`ScimException` — `invalidPath` 뒤에:

```java
    /** RFC 7644 §3.12 — 필터 문법이 틀렸거나 지원하지 않는 속성·연산자 조합이다. */
    public static ScimException invalidFilter(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidFilter", detail);
    }

    /** RFC 7644 §3.12 — 값이 없거나 작업과 맞지 않는다. 조회 파라미터(정렬·페이지·속성 선택)의 잘못된 값에 쓴다. */
    public static ScimException invalidValue(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidValue", detail);
    }

    /** RFC 7644 §3.12 — 서비스 제공자가 지원하지 않는 작업이다(서버 루트 조회 등). */
    public static ScimException notImplemented(String detail) {
        return new ScimException(HttpStatus.NOT_IMPLEMENTED, null, detail);
    }
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimResourceType.java`:

```java
package dev.starryeye.organization.scim;

import java.util.Locale;
import java.util.Set;

/**
 * 조회가 다루는 두 리소스. 스키마 URN, 정렬 가능한 속성(인덱스 키), 응답에 담기는 속성 경로를 한 곳에 둔다.
 *
 * <p>속성 경로는 우리 DTO({@code ScimUser}/{@code ScimGroup})가 실제로 내보내는 것만이다 — {@code attributes}·
 * {@code excludedAttributes} 에 그 밖의 이름이 오면 거절한다(S-1 설계 §4.5).
 */
public enum ScimResourceType {

    USER(ScimSchemas.USER, "userName", Set.of(
            "schemas", "id", "externalid", "username",
            "name", "name.formatted", "name.familyname", "name.givenname",
            "displayname", "emails", "emails.value", "emails.type", "emails.primary",
            "active", "meta", "meta.resourcetype", "meta.location")),

    GROUP(ScimSchemas.GROUP, "displayName", Set.of(
            "schemas", "id", "externalid", "displayname",
            "members", "members.value", "members.type", "members.display",
            "meta", "meta.resourcetype", "meta.location"));

    private final String schemaUrn;
    private final String sortAttribute;
    private final Set<String> attributes;

    ScimResourceType(String schemaUrn, String sortAttribute, Set<String> attributes) {
        this.schemaUrn = schemaUrn;
        this.sortAttribute = sortAttribute;
        this.attributes = attributes;
    }

    public String schemaUrn() {
        return schemaUrn;
    }

    /** 정렬할 수 있는 유일한 속성 — 목록을 이어 읽는 인덱스의 키다(S-1 설계 §4.3). */
    public String sortAttribute() {
        return sortAttribute;
    }

    /** 응답에 담기는 속성 경로. 소문자다. */
    public Set<String> attributes() {
        return attributes;
    }

    /**
     * 이 리소스의 core 스키마 URN 이 붙었으면 떼고 소문자로 준다. 다른 스키마의 URN 이면 null.
     * 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1).
     */
    public String localName(String path) {
        String prefix = schemaUrn + ":";
        String local = path.regionMatches(true, 0, prefix, 0, prefix.length())
                ? path.substring(prefix.length())
                : path;
        return local.startsWith("urn:") ? null : local.toLowerCase(Locale.ROOT);
    }
}
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimFilter.java`:

```java
package dev.starryeye.organization.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SCIM 필터의 좁은 부분집합 — {@code 속성 eq 값 (and 속성 eq 값)*} 만 받는다 (S-1 설계 §4.1).
 *
 * <pre>
 * filter   = term *( SP "and" SP term )
 * term     = attrPath SP "eq" SP compValue
 * attrPath = [ 리소스의 core 스키마 URN ":" ] attrName
 * compValue = JSON 문자열 / "true" / "false"
 * </pre>
 *
 * <p>RFC 7644 §3.4.2.2 는 필터 전체를 선택 기능으로 두고, 지원하지 않는 조합에는 {@code invalidFilter} 를
 * 돌려주라고 정한다. Entra 는 {@code eq}·{@code and} 만 쓴다고 문서에 밝혔고 Okta 는 {@code eq} 만 쓴다. 이
 * 문법에 맞지 않는 모든 것 — {@code or}·{@code not}, 다른 연산자, 괄호, 대괄호 값 경로, 하위 속성,
 * 숫자·{@code null} — 은 한 규칙으로 거절한다.
 *
 * <p>여기서는 모양만 본다. 어떤 속성을 받을지, 값의 타입이 맞는지는 리소스마다 달라 조회 쪽이 판단한다.
 *
 * @param terms 한 개 이상. 모두 {@code and} 로 묶인다
 */
public record ScimFilter(List<Term> terms) {

    /**
     * @param attribute 스키마 URN 을 뗀 속성 이름, 소문자
     * @param value     {@link String} 또는 {@link Boolean}
     */
    public record Term(String attribute, Object value) {
    }

    public static ScimFilter parse(String raw, ScimResourceType type) {
        if (raw == null || raw.isEmpty()) {
            throw ScimException.invalidFilter("필터가 비어 있습니다");
        }
        Parser parser = new Parser(raw, type);
        List<Term> terms = new ArrayList<>();
        terms.add(parser.term());
        while (!parser.atEnd()) {
            parser.space();
            parser.keyword("and");
            parser.space();
            terms.add(parser.term());
        }
        return new ScimFilter(List.copyOf(terms));
    }

    private static final class Parser {

        private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[a-z][a-z0-9_-]*");

        private final String text;
        private final ScimResourceType type;
        private int position;

        Parser(String text, ScimResourceType type) {
            this.text = text;
            this.type = type;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        Term term() {
            String attribute = attributePath();
            space();
            keyword("eq");
            space();
            return new Term(attribute, value());
        }

        /** 공백 한 칸(ABNF 의 SP). 두 칸 이상은 문법 밖이다. */
        void space() {
            if (atEnd() || text.charAt(position) != ' ') {
                throw fail("공백 한 칸이 와야 합니다 (위치 " + position + ")");
            }
            position++;
        }

        void keyword(String word) {
            if (!text.regionMatches(true, position, word, 0, word.length())) {
                throw fail("'" + word + "' 가 와야 합니다 (위치 " + position + ")");
            }
            position += word.length();
        }

        private String attributePath() {
            int start = position;
            while (!atEnd() && text.charAt(position) != ' ') {
                position++;
            }
            String path = text.substring(start, position);
            String local = type.localName(path);
            if (local == null || !ATTRIBUTE_NAME.matcher(local).matches()) {
                throw fail("지원하지 않는 속성 경로입니다: '" + path + "'");
            }
            return local;
        }

        private Object value() {
            if (!atEnd() && text.charAt(position) == '"') {
                return string();
            }
            int start = position;
            while (!atEnd() && text.charAt(position) != ' ') {
                position++;
            }
            String token = text.substring(start, position);
            if (token.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (token.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
            throw fail("지원하지 않는 값입니다: '" + token + "' — 문자열과 true/false 만 받습니다");
        }

        /** JSON 문자열(RFC 8259 §7) — 따옴표 안의 이스케이프를 푼다. */
        private String string() {
            StringBuilder out = new StringBuilder();
            position++;
            while (!atEnd()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    break;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw fail("알 수 없는 이스케이프입니다: \\" + escaped);
                }
            }
            throw fail("문자열이 닫히지 않았습니다");
        }

        private char unicode() {
            if (position + 4 > text.length()) {
                throw fail("\\u 뒤에는 16진수 네 자리가 와야 합니다");
            }
            String hex = text.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw fail("\\u 뒤에는 16진수 네 자리가 와야 합니다: " + hex);
            }
        }

        private ScimException fail(String detail) {
            return ScimException.invalidFilter(detail + " — filter: " + text);
        }
    }
}
```

`ATTRIBUTE_NAME` 이 소문자 패턴인 이유: `localName` 이 이미 소문자로 바꿔 준다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimFilterTest'`
Expected: PASS

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add connector-scim
git commit -m "feat: SCIM 필터 파서 — eq 와 and 만, 나머지는 invalidFilter" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 5: 조회 파라미터와 속성 선택

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimSchemas.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimJson.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimText.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimAttributeProjection.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimQuery.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimSearchRequest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimAttributeProjectionTest.java`, `ScimQueryTest.java` (신규)

**Interfaces:**
- Consumes: `ScimResourceType`, `ScimFilter.parse`, `ScimException.invalidValue/invalidSyntax/invalidFilter` (Task 4)
- Produces: `ScimSchemas.LIST_RESPONSE`, `ScimSchemas.SEARCH_REQUEST`
- Produces: 패키지 전용 `ScimJson.tree(Object) → ObjectNode`; `ScimText.lower(String)`, `ScimText.sameIgnoringCase(String stored, String asked)`
- Produces: `ScimAttributeProjection` — `static all()`, `static of(ScimResourceType, List<String> attributes, List<String> excluded)`, `static fromRequest(ScimResourceType, ServerRequest)`, `static List<String> split(String)`, `boolean includes(String attribute)`, `ObjectNode apply(ObjectNode resource)`
- Produces: `record ScimQuery(ScimFilter filter, long startIndex, int count, boolean descending, ScimAttributeProjection projection)` — `MAX_COUNT = 100`, `static fromRequest(ScimResourceType, ServerRequest)`, `static fromSearch(ScimResourceType, ScimSearchRequest)`, 패키지 전용 `static of(ScimResourceType, String filter, Long startIndex, Long count, String sortBy, String sortOrder, List<String> attributes, List<String> excludedAttributes)`
- Produces: `record ScimSearchRequest(List<String> schemas, List<String> attributes, List<String> excludedAttributes, String filter, String sortBy, String sortOrder, Long startIndex, Long count)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`connector-scim/src/test/java/dev/starryeye/organization/scim/ScimAttributeProjectionTest.java`:

```java
package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.model.DirectoryUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimAttributeProjectionTest {

    private static ObjectNode 김철수() {
        return ScimJson.tree(ScimMapper.toScimUser(
                new DirectoryUser("kim", "emp-1", "kim", "김철수", "kim@example.com", true)));
    }

    private static List<String> 필드(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ScimAttributeProjection 선택(List<String> attributes, List<String> excluded) {
        return ScimAttributeProjection.of(ScimResourceType.USER, attributes, excluded);
    }

    @Test
    @DisplayName("attributes 는 고른 속성만 남기되 id 와 schemas 는 항상 남긴다")
    void attributes_는_고른_것만_남긴다() {
        // when
        ObjectNode node = 선택(List.of("userName"), List.of()).apply(김철수());

        // then
        assertThat(필드(node)).containsExactlyInAnyOrder("schemas", "id", "userName");
    }

    @Test
    @DisplayName("하위 속성을 고르면 그 복합 속성 안에서도 그것만 남긴다")
    void 하위_속성만_남긴다() {
        // when
        ObjectNode node = 선택(List.of("emails.value"), List.of()).apply(김철수());

        // then
        assertThat(필드(node)).containsExactlyInAnyOrder("schemas", "id", "emails");
        assertThat(필드((ObjectNode) node.get("emails").get(0))).containsExactly("value");
    }

    @Test
    @DisplayName("excludedAttributes 는 뺀 속성만 지우고 id 는 빼지 못한다")
    void excludedAttributes_는_뺀_것만_지운다() {
        // when
        ObjectNode node = 선택(List.of(), List.of("emails", "meta", "id")).apply(김철수());

        // then
        assertThat(필드(node)).contains("id", "userName", "displayName").doesNotContain("emails", "meta");
    }

    @Test
    @DisplayName("하위 속성을 빼면 그 하위 속성만 지운다")
    void 하위_속성을_뺀다() {
        // when
        ObjectNode node = 선택(List.of(), List.of("emails.type")).apply(김철수());

        // then
        assertThat(필드((ObjectNode) node.get("emails").get(0))).contains("value").doesNotContain("type");
    }

    @Test
    @DisplayName("속성 이름은 대소문자를 가리지 않고 URN 이 붙어도 된다")
    void 이름은_대소문자와_URN_을_가리지_않는다() {
        // when
        ObjectNode node = 선택(List.of("urn:ietf:params:scim:schemas:core:2.0:User:USERNAME"), List.of())
                .apply(김철수());

        // then
        assertThat(필드(node)).containsExactlyInAnyOrder("schemas", "id", "userName");
    }

    @Test
    @DisplayName("조직의 members 가 응답에 남는지 알려 준다 — 대소문자가 달라도 같다")
    void members_가_남는지_알려_준다() {
        // when, then
        assertThat(ScimAttributeProjection.all().includes("members")).isTrue();
        assertThat(ScimAttributeProjection.of(ScimResourceType.GROUP, List.of(), List.of("MEMBERS"))
                .includes("members")).isFalse();
        assertThat(ScimAttributeProjection.of(ScimResourceType.GROUP, List.of("displayName"), List.of())
                .includes("members")).isFalse();
        assertThat(ScimAttributeProjection.of(ScimResourceType.GROUP, List.of("members.value"), List.of())
                .includes("members")).isTrue();
    }

    @Test
    @DisplayName("둘을 함께 주거나 모르는 속성 이름을 주면 invalidValue 다")
    void 함께_주거나_모르는_이름이면_거절한다() {
        assertThatThrownBy(() -> 선택(List.of("userName"), List.of("emails")))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
        assertThatThrownBy(() -> 선택(List.of("nickName"), List.of()))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
        assertThatThrownBy(() -> 선택(List.of(), List.of("members")))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
    }

    @Test
    @DisplayName("URL 의 쉼표 목록을 나눈다")
    void 쉼표_목록을_나눈다() {
        assertThat(ScimAttributeProjection.split("userName, emails.value,,")).containsExactly("userName", "emails.value");
        assertThat(ScimAttributeProjection.split(null)).isEmpty();
    }
}
```

`connector-scim/src/test/java/dev/starryeye/organization/scim/ScimQueryTest.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimSearchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimQueryTest {

    private static ScimQuery 조회(Long startIndex, Long count) {
        return ScimQuery.of(ScimResourceType.USER, null, startIndex, count, null, null, null, null);
    }

    private static void 값_오류(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ScimException.class,
                e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
    }

    @Test
    @DisplayName("아무것도 없으면 1번부터 100건, 오름차순, 필터 없음이다")
    void 기본값() {
        // when
        ScimQuery query = 조회(null, null);

        // then
        assertThat(query.filter()).isNull();
        assertThat(query.startIndex()).isEqualTo(1);
        assertThat(query.count()).isEqualTo(100);
        assertThat(query.descending()).isFalse();
    }

    @Test
    @DisplayName("startIndex 가 1 미만이면 1, count 가 음수면 0, 100 을 넘으면 100 이다 (RFC 7644 §3.4.2.4)")
    void 페이지_경계() {
        assertThat(조회(0L, null).startIndex()).isEqualTo(1);
        assertThat(조회(-5L, null).startIndex()).isEqualTo(1);
        assertThat(조회(null, -1L).count()).isZero();
        assertThat(조회(null, 0L).count()).isZero();
        assertThat(조회(null, 101L).count()).isEqualTo(100);
    }

    @Test
    @DisplayName("URL 의 startIndex·count 가 정수가 아니면 invalidValue 다")
    void 정수가_아니면_거절한다() {
        값_오류(() -> ScimQuery.fromRequest(ScimResourceType.USER,
                MockServerRequest.builder().queryParam("startIndex", "abc").build()));
        값_오류(() -> ScimQuery.fromRequest(ScimResourceType.USER,
                MockServerRequest.builder().queryParam("count", "1.5").build()));
    }

    @Test
    @DisplayName("URL 파라미터를 읽는다 — 필터, 페이지, 내림차순, 속성 선택")
    void URL_파라미터를_읽는다() {
        // when
        ScimQuery query = ScimQuery.fromRequest(ScimResourceType.USER, MockServerRequest.builder()
                .queryParam("filter", "userName eq \"kim\"")
                .queryParam("startIndex", "11")
                .queryParam("count", "5")
                .queryParam("sortBy", "userName")
                .queryParam("sortOrder", "DESCENDING")
                .queryParam("attributes", "userName")
                .build());

        // then
        assertThat(query.filter().terms()).containsExactly(new ScimFilter.Term("username", "kim"));
        assertThat(query.startIndex()).isEqualTo(11);
        assertThat(query.count()).isEqualTo(5);
        assertThat(query.descending()).isTrue();
        assertThat(query.projection().includes("displayName")).isFalse();
    }

    @Test
    @DisplayName("정렬은 인덱스 키만, 방향은 ascending·descending 만 받는다")
    void 정렬_규칙() {
        assertThat(ScimQuery.of(ScimResourceType.USER, null, null, null,
                "urn:ietf:params:scim:schemas:core:2.0:User:userName", "ascending", null, null).descending()).isFalse();
        assertThat(ScimQuery.of(ScimResourceType.GROUP, null, null, null,
                "displayName", "descending", null, null).descending()).isTrue();
        값_오류(() -> ScimQuery.of(ScimResourceType.USER, null, null, null, "displayName", null, null, null));
        값_오류(() -> ScimQuery.of(ScimResourceType.USER, null, null, null, null, "up", null, null));
    }

    @Test
    @DisplayName("빈 필터는 invalidFilter 다")
    void 빈_필터는_거절한다() {
        assertThatThrownBy(() -> ScimQuery.of(ScimResourceType.USER, "", null, null, null, null, null, null))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidFilter"));
    }

    @Test
    @DisplayName(".search 본문을 같은 조회로 바꾸고, SearchRequest 스키마가 없으면 invalidSyntax 다")
    void search_본문을_바꾼다() {
        // given
        var body = new ScimSearchRequest(List.of(ScimSchemas.SEARCH_REQUEST), List.of("userName"), null,
                "userName eq \"kim\"", null, null, 1L, 10L);
        var 스키마없음 = new ScimSearchRequest(null, null, null, null, null, null, null, null);

        // when
        ScimQuery query = ScimQuery.fromSearch(ScimResourceType.USER, body);

        // then
        assertThat(query.count()).isEqualTo(10);
        assertThat(query.filter().terms()).hasSize(1);
        assertThatThrownBy(() -> ScimQuery.fromSearch(ScimResourceType.USER, 스키마없음))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidSyntax"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimAttributeProjectionTest' --tests '*ScimQueryTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다**

`ScimSchemas` — 기존 상수들 옆에:

```java
    /** RFC 7644 §3.4.2 — 조회 응답. */
    public static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    /** RFC 7644 §3.4.3 — {@code POST /.search} 본문. */
    public static final String SEARCH_REQUEST = "urn:ietf:params:scim:api:messages:2.0:SearchRequest";
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimJson.java`:

```java
package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * SCIM DTO 를 JSON 트리로 바꾼다. 속성 선택(RFC 7644 §3.9)이 트리에서 속성을 지우기 때문이다.
 * DTO 의 직렬화 규칙({@code @JsonInclude(NON_NULL)})은 애노테이션이라 이 매퍼에도 그대로 적용된다.
 */
final class ScimJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScimJson() {
    }

    static ObjectNode tree(Object resource) {
        return MAPPER.valueToTree(resource);
    }
}
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimText.java`:

```java
package dev.starryeye.organization.scim;

import java.util.Locale;

/**
 * 대소문자를 가리지 않는 비교(RFC 7643 {@code caseExact=false}). 저장소의 인덱스 키({@code Keys.indexKey})와
 * <b>같은 규칙</b>이어야 한다 — 인덱스로 찾은 후보를 메모리에서 다시 확인할 때 둘이 어긋나면 찾은 것을 버린다.
 */
final class ScimText {

    private ScimText() {
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** 저장된 값이 없으면 어떤 값과도 같지 않다. */
    static boolean sameIgnoringCase(String stored, String asked) {
        return stored != null && lower(stored).equals(lower(asked));
    }
}
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimAttributeProjection.java`:

```java
package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code attributes}·{@code excludedAttributes} (RFC 7644 §3.9) — 응답에 담을 속성을 고른다.
 *
 * <p>리소스를 돌려주는 모든 응답에 적용한다. 이름은 최상위 속성과 한 단계 하위 속성이며, 대소문자를 가리지
 * 않고 URN 이 붙어도 된다. {@code id} 와 {@code schemas} 는 어느 경우에도 남긴다({@code id} 는 RFC 7643 에서
 * {@code returned: always}). 둘을 함께 주거나 모르는 이름을 주면 {@code invalidValue} 다(S-1 설계 §4.5).
 */
public final class ScimAttributeProjection {

    private static final Set<String> ALWAYS = Set.of("id", "schemas");
    private static final ScimAttributeProjection ALL = new ScimAttributeProjection(Set.of(), Set.of());

    /** 비어 있으면 제한 없음. 소문자 경로. */
    private final Set<String> include;
    private final Set<String> exclude;

    private ScimAttributeProjection(Set<String> include, Set<String> exclude) {
        this.include = include;
        this.exclude = exclude;
    }

    public static ScimAttributeProjection all() {
        return ALL;
    }

    public static ScimAttributeProjection of(ScimResourceType type, List<String> attributes, List<String> excluded) {
        List<String> chosen = attributes == null ? List.of() : attributes;
        List<String> dropped = excluded == null ? List.of() : excluded;
        if (!chosen.isEmpty() && !dropped.isEmpty()) {
            throw ScimException.invalidValue("attributes 와 excludedAttributes 는 함께 쓸 수 없습니다");
        }
        return new ScimAttributeProjection(names(type, chosen), names(type, dropped));
    }

    public static ScimAttributeProjection fromRequest(ScimResourceType type, ServerRequest request) {
        return of(type,
                split(request.queryParam("attributes").orElse(null)),
                split(request.queryParam("excludedAttributes").orElse(null)));
    }

    /** URL 쿼리의 쉼표 목록. 비어 있으면 빈 목록. */
    public static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static Set<String> names(ScimResourceType type, List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : raw) {
            String local = type.localName(name);
            if (local == null || !type.attributes().contains(local)) {
                throw ScimException.invalidValue("알 수 없는 속성입니다: " + name);
            }
            result.add(local);
        }
        return Set.copyOf(result);
    }

    /** 이 최상위 속성이 응답에 남는가. 조직의 {@code members} 를 읽을지 정할 때 쓴다. */
    public boolean includes(String attribute) {
        String name = attribute.toLowerCase(Locale.ROOT);
        if (exclude.contains(name)) {
            return false;
        }
        if (include.isEmpty() || ALWAYS.contains(name) || include.contains(name)) {
            return true;
        }
        return include.stream().anyMatch(path -> path.startsWith(name + "."));
    }

    public ObjectNode apply(ObjectNode resource) {
        if (!include.isEmpty()) {
            for (String field : fieldNames(resource)) {
                String name = field.toLowerCase(Locale.ROOT);
                if (ALWAYS.contains(name) || include.contains(name)) {
                    continue;
                }
                Set<String> subs = subAttributes(include, name);
                if (subs.isEmpty()) {
                    resource.remove(field);
                } else {
                    eachObject(resource.get(field), node -> retain(node, subs));
                }
            }
        }
        for (String path : exclude) {
            int dot = path.indexOf('.');
            if (dot < 0) {
                if (!ALWAYS.contains(path)) {
                    removeIgnoringCase(resource, path);
                }
            } else {
                String parent = field(resource, path.substring(0, dot));
                if (parent != null) {
                    String sub = path.substring(dot + 1);
                    eachObject(resource.get(parent), node -> removeIgnoringCase(node, sub));
                }
            }
        }
        return resource;
    }

    private static Set<String> subAttributes(Set<String> paths, String parent) {
        return paths.stream()
                .filter(path -> path.startsWith(parent + "."))
                .map(path -> path.substring(parent.length() + 1))
                .collect(Collectors.toSet());
    }

    /** 복합 속성은 객체이거나 객체의 배열이다({@code emails}, {@code members}). */
    private static void eachObject(JsonNode node, java.util.function.Consumer<ObjectNode> action) {
        if (node instanceof ObjectNode object) {
            action.accept(object);
        } else if (node != null && node.isArray()) {
            node.forEach(element -> {
                if (element instanceof ObjectNode object) {
                    action.accept(object);
                }
            });
        }
    }

    private static void retain(ObjectNode node, Set<String> subs) {
        for (String field : fieldNames(node)) {
            if (!subs.contains(field.toLowerCase(Locale.ROOT))) {
                node.remove(field);
            }
        }
    }

    private static void removeIgnoringCase(ObjectNode node, String name) {
        String field = field(node, name);
        if (field != null) {
            node.remove(field);
        }
    }

    private static String field(ObjectNode node, String name) {
        for (String field : fieldNames(node)) {
            if (field.equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimSearchRequest.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** RFC 7644 §3.4.3 — {@code POST /.search} 본문. URL 조회와 같은 파라미터를 본문으로 보낸다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimSearchRequest(
        List<String> schemas,
        List<String> attributes,
        List<String> excludedAttributes,
        String filter,
        String sortBy,
        String sortOrder,
        Long startIndex,
        Long count
) {
}
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimQuery.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimSearchRequest;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.List;

/**
 * 조회 파라미터 한 벌 (S-1 설계 §4). URL 쿼리와 {@code .search} 본문이 같은 이 형태로 바뀐다.
 *
 * <p>페이지 규칙(RFC 7644 §3.4.2.4)을 여기서 한 번에 정리한다 — {@code startIndex<1} 은 1, {@code count} 가
 * 없으면 100, 음수는 0, 100 초과는 100. 정렬은 인덱스 키만(§4.3). 잘못된 값은 {@code invalidValue}, 필터는
 * {@code invalidFilter} 다.
 *
 * @param filter     없으면 null — 필터 없는 목록
 * @param startIndex 1 부터
 * @param count      0~100
 */
public record ScimQuery(ScimFilter filter, long startIndex, int count, boolean descending,
                        ScimAttributeProjection projection) {

    /** 페이지 상한이자 기본값. ServiceProviderConfig 의 {@code filter.maxResults} 와 같다. */
    public static final int MAX_COUNT = 100;

    public static ScimQuery fromRequest(ScimResourceType type, ServerRequest request) {
        return of(type,
                request.queryParam("filter").orElse(null),
                number(request.queryParam("startIndex").orElse(null), "startIndex"),
                number(request.queryParam("count").orElse(null), "count"),
                request.queryParam("sortBy").orElse(null),
                request.queryParam("sortOrder").orElse(null),
                ScimAttributeProjection.split(request.queryParam("attributes").orElse(null)),
                ScimAttributeProjection.split(request.queryParam("excludedAttributes").orElse(null)));
    }

    public static ScimQuery fromSearch(ScimResourceType type, ScimSearchRequest body) {
        if (body.schemas() == null || !body.schemas().contains(ScimSchemas.SEARCH_REQUEST)) {
            throw ScimException.invalidSyntax("SearchRequest 스키마가 없습니다: " + ScimSchemas.SEARCH_REQUEST);
        }
        return of(type, body.filter(), body.startIndex(), body.count(), body.sortBy(), body.sortOrder(),
                body.attributes(), body.excludedAttributes());
    }

    static ScimQuery of(ScimResourceType type, String filter, Long startIndex, Long count,
                        String sortBy, String sortOrder, List<String> attributes, List<String> excludedAttributes) {
        ScimFilter parsed = filter == null ? null : ScimFilter.parse(filter, type);
        long start = startIndex == null || startIndex < 1 ? 1 : startIndex;
        int size = count == null ? MAX_COUNT : (int) Math.max(0, Math.min(count, MAX_COUNT));
        checkSortBy(type, sortBy);
        return new ScimQuery(parsed, start, size, descending(sortOrder),
                ScimAttributeProjection.of(type, attributes, excludedAttributes));
    }

    private static Long number(String raw, String name) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ScimException.invalidValue(name + " 는 정수여야 합니다: " + raw);
        }
    }

    private static void checkSortBy(ScimResourceType type, String sortBy) {
        if (sortBy == null) {
            return;
        }
        String local = type.localName(sortBy);
        if (local == null || !local.equals(ScimText.lower(type.sortAttribute()))) {
            throw ScimException.invalidValue(
                    "정렬은 " + type.sortAttribute() + " 로만 할 수 있습니다: " + sortBy);
        }
    }

    private static boolean descending(String sortOrder) {
        if (sortOrder == null || sortOrder.equalsIgnoreCase("ascending")) {
            return false;
        }
        if (sortOrder.equalsIgnoreCase("descending")) {
            return true;
        }
        throw ScimException.invalidValue("sortOrder 는 ascending 또는 descending 입니다: " + sortOrder);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: 전부 PASS (`MockServerRequest` 가 없다고 나오면 connector-scim 의 테스트 의존성에 spring-test 가 있는지 `./gradlew :connector-scim:dependencies --configuration testRuntimeClasspath | grep spring-test` 로 확인하고, 없으면 `testImplementation 'org.springframework:spring-test'` 를 더한다)

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add connector-scim
git commit -m "feat: SCIM 조회 파라미터와 attributes·excludedAttributes 속성 선택" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 6: 조회 엔진

**Files:**
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeQueryRepository.java`
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakePageBookmarkRepository.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPager.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserListing.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupListing.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimListResponse.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java` (`toScimGroup(GroupHeader)`)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserListingTest.java`, `ScimGroupListingTest.java` (신규)

**Interfaces:**
- Consumes: `DirectoryQueryRepository` (Task 2), `PageBookmarkRepository`·`ListingKind`·`PageBookmark` (Task 3), `ScimQuery`·`ScimAttributeProjection`·`ScimJson`·`ScimText` (Task 5), `ScimFilter` (Task 4)
- Produces: `FakeQueryRepository(FakeStateRepository state)` — `public final List<String> calls` 에 `"countUsers"`, `"countGroups"`, `"skipUsers:<n>"`, `"skipGroups:<n>"`, `"listUsers:<from>:<limit>"`, `"listGroupHeaders:<from>:<limit>"`, `"findUsersByUserName:<v>"`, `"findUsersByExternalId:<v>"`, `"findGroupHeadersByDisplayName:<v>"`, `"findGroupHeadersByExternalId:<v>"` 를 기록. 위치는 0 부터의 오프셋 문자열
- Produces: `FakePageBookmarkRepository` — `public final Map<String, PageBookmark> saved`, `static String key(ListingKind, boolean descending, long startIndex)` = `kind + (descending ? ":DESC:" : ":ASC:") + startIndex`
- Produces: `ScimUserListing(DirectoryStateRepository, DirectoryQueryRepository, PageBookmarkRepository)` · `ScimGroupListing(같은 셋)` — `Mono<ScimListResponse> list(ScimQuery query)`
- Produces: `record ScimListResponse(List<String> schemas, long totalResults, long startIndex, int itemsPerPage, @JsonProperty("Resources") List<JsonNode> resources)` — `static of(ScimQuery, long totalResults, List<? extends JsonNode> resources)`
- Produces: `ScimMapper.toScimGroup(GroupHeader)` — `members` 가 null 인 ScimGroup

- [ ] **Step 1: 가짜를 만든다**

`core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeQueryRepository.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.query.Page;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@link FakeStateRepository} 의 맵을 그대로 읽는 조회 저장소. 실제 저장소처럼 {@code userName}·조직명은
 * 대소문자를 가리지 않고 소문자 순(같으면 id 순)으로 정렬하며, {@code limit} 을 채우면 끝이어도 다음 위치를
 * 준다(DynamoDB 의 LastEvaluatedKey 와 같다). 위치는 0 부터의 오프셋 문자열이다.
 */
public class FakeQueryRepository implements DirectoryQueryRepository {

    private final FakeStateRepository state;

    /** 불린 메서드와 인자. 책갈피가 먹는지(세기·건너뛰기가 다시 불리지 않는지) 단언하는 데 쓴다. */
    public final List<String> calls = new ArrayList<>();

    public FakeQueryRepository(FakeStateRepository state) {
        this.state = state;
    }

    @Override
    public Flux<DirectoryUser> findUsersByUserName(String userName) {
        calls.add("findUsersByUserName:" + userName);
        return Flux.fromIterable(sortedUsers(false))
                .filter(user -> same(user.userName(), userName));
    }

    @Override
    public Flux<DirectoryUser> findUsersByExternalId(String externalId) {
        calls.add("findUsersByExternalId:" + externalId);
        return Flux.fromIterable(sortedUsers(false))
                .filter(user -> externalId.equals(user.externalId()));
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByDisplayName(String displayName) {
        calls.add("findGroupHeadersByDisplayName:" + displayName);
        return Flux.fromIterable(sortedGroups(false))
                .filter(group -> same(group.displayName(), displayName));
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByExternalId(String externalId) {
        calls.add("findGroupHeadersByExternalId:" + externalId);
        return Flux.fromIterable(sortedGroups(false))
                .filter(group -> externalId.equals(group.externalId()));
    }

    @Override
    public Mono<Page<DirectoryUser>> listUsers(String from, int limit, boolean descending) {
        calls.add("listUsers:" + from + ":" + limit);
        return Mono.just(slice(sortedUsers(descending), from, limit));
    }

    @Override
    public Mono<Page<GroupHeader>> listGroupHeaders(String from, int limit, boolean descending) {
        calls.add("listGroupHeaders:" + from + ":" + limit);
        return Mono.just(slice(sortedGroups(descending), from, limit));
    }

    @Override
    public Mono<Long> countUsers() {
        calls.add("countUsers");
        return Mono.just((long) state.users.size());
    }

    @Override
    public Mono<Long> countGroups() {
        calls.add("countGroups");
        return Mono.just((long) state.groups.size());
    }

    @Override
    public Mono<String> skipUsers(long n, boolean descending) {
        calls.add("skipUsers:" + n);
        return skip(n, state.users.size());
    }

    @Override
    public Mono<String> skipGroups(long n, boolean descending) {
        calls.add("skipGroups:" + n);
        return skip(n, state.groups.size());
    }

    private static Mono<String> skip(long n, int size) {
        if (n <= 0 || size == 0) {
            return Mono.empty();
        }
        return Mono.just(String.valueOf(Math.min(n, size)));
    }

    private static <T> Page<T> slice(List<T> sorted, String from, int limit) {
        int offset = from == null ? 0 : Integer.parseInt(from);
        if (offset >= sorted.size()) {
            return new Page<>(List.of(), null);
        }
        int end = Math.min(offset + limit, sorted.size());
        List<T> items = sorted.subList(offset, end);
        return new Page<>(items, items.size() == limit ? String.valueOf(end) : null);
    }

    private List<DirectoryUser> sortedUsers(boolean descending) {
        List<DirectoryUser> users = new ArrayList<>(state.users.values());
        users.sort(Comparator.comparing((DirectoryUser user) -> lower(user.userName() == null ? user.id() : user.userName()))
                .thenComparing(DirectoryUser::id));
        if (descending) {
            Collections.reverse(users);
        }
        return users;
    }

    private List<GroupHeader> sortedGroups(boolean descending) {
        List<GroupHeader> groups = new ArrayList<>(state.groups.values().stream()
                .map(FakeQueryRepository::header)
                .toList());
        groups.sort(Comparator.comparing((GroupHeader group) -> lower(group.displayName() == null ? group.id() : group.displayName()))
                .thenComparing(GroupHeader::id));
        if (descending) {
            Collections.reverse(groups);
        }
        return groups;
    }

    private static GroupHeader header(DirectoryGroup group) {
        return new GroupHeader(group.id(), group.externalId(), group.displayName());
    }

    private static boolean same(String stored, String asked) {
        return stored != null && lower(stored).equals(lower(asked));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
```

`core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakePageBookmarkRepository.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/** 만료가 없는 책갈피. 저장된 것을 {@link #saved} 로 들여다본다. */
public class FakePageBookmarkRepository implements PageBookmarkRepository {

    public final Map<String, PageBookmark> saved = new LinkedHashMap<>();

    public static String key(ListingKind kind, boolean descending, long startIndex) {
        return kind + (descending ? ":DESC:" : ":ASC:") + startIndex;
    }

    @Override
    public Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex) {
        return Mono.justOrEmpty(saved.get(key(kind, descending, startIndex)));
    }

    @Override
    public Mono<Void> save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark) {
        saved.put(key(kind, descending, startIndex), bookmark);
        return Mono.empty();
    }
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserListingTest.java`:

```java
package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimUserListingTest {

    private FakeStateRepository state;
    private FakeQueryRepository query;
    private FakePageBookmarkRepository bookmarks;
    private ScimUserListing listing;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        query = new FakeQueryRepository(state);
        bookmarks = new FakePageBookmarkRepository();
        listing = new ScimUserListing(state, query, bookmarks);
    }

    /** u000 … u(n-1). 아이디와 userName 이 같고 externalId 는 ext-i 다. */
    private void 직원들을_둔다(int n) {
        for (int i = 0; i < n; i++) {
            String id = "u%03d".formatted(i);
            state.saveUser(new DirectoryUser(id, "ext-" + i, id, "직원 " + i, null, true)).block();
        }
    }

    private void 직원(String id, String userName, String externalId, boolean active) {
        state.saveUser(new DirectoryUser(id, externalId, userName, "직원 " + id, null, active)).block();
    }

    private ScimListResponse 조회(String filter, long startIndex, long count) {
        return listing.list(ScimQuery.of(ScimResourceType.USER, filter, startIndex, count,
                null, null, null, null)).block();
    }

    private static List<String> 아이디들(ScimListResponse response) {
        return response.resources().stream().map(node -> node.get("id").asText()).toList();
    }

    private static void 필터_오류(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ScimException.class,
                e -> assertThat(e.getScimType()).isEqualTo("invalidFilter"));
    }

    @Test
    @DisplayName("필터 없는 첫 페이지는 전체를 세고 다음 페이지의 책갈피를 남긴다")
    void 첫_페이지는_세고_책갈피를_남긴다() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 1, 100);

        // then
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(page.itemsPerPage()).isEqualTo(100);
        assertThat(아이디들(page)).startsWith("u000").endsWith("u099").hasSize(100);
        assertThat(bookmarks.saved).containsKey(FakePageBookmarkRepository.key(ListingKind.USER, false, 101));
        assertThat(bookmarks.saved.get(FakePageBookmarkRepository.key(ListingKind.USER, false, 101)).totalResults())
                .isEqualTo(250);
    }

    @Test
    @DisplayName("책갈피가 있으면 세지도 건너뛰지도 않고 그 자리부터 이어 읽는다")
    void 책갈피로_이어_읽는다() {
        // given
        직원들을_둔다(250);
        조회(null, 1, 100);
        query.calls.clear();

        // when
        ScimListResponse page = 조회(null, 101, 100);

        // then
        assertThat(아이디들(page)).startsWith("u100").endsWith("u199");
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(query.calls).containsExactly("listUsers:100:100");
    }

    @Test
    @DisplayName("책갈피가 없는 중간 페이지는 앞을 건너뛰고 세어서 정확한 페이지를 준다")
    void 책갈피가_없으면_건너뛴다() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 101, 100);

        // then
        assertThat(아이디들(page)).startsWith("u100").endsWith("u199");
        assertThat(query.calls).contains("skipUsers:100", "countUsers");
    }

    @Test
    @DisplayName("마지막 페이지는 남은 만큼만 주고 책갈피를 더 남기지 않는다")
    void 마지막_페이지() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 201, 100);

        // then
        assertThat(page.itemsPerPage()).isEqualTo(50);
        assertThat(bookmarks.saved).doesNotContainKey(FakePageBookmarkRepository.key(ListingKind.USER, false, 251));
    }

    @Test
    @DisplayName("startIndex 가 전체보다 크면 빈 Resources 와 전체 수를 준다")
    void 전체보다_큰_startIndex() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 1000, 100);

        // then
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(page.resources()).isEmpty();
    }

    @Test
    @DisplayName("count=0 이면 Resources 없이 전체 수만 준다")
    void count_0_은_전체_수만() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 1, 0);

        // then
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(page.resources()).isNull();
        assertThat(query.calls).doesNotContain("listUsers:null:0");
    }

    @Test
    @DisplayName("내림차순이면 역순으로 준다")
    void 내림차순() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = listing.list(ScimQuery.of(ScimResourceType.USER, null, 1L, 3L,
                "userName", "descending", null, null)).block();

        // then
        assertThat(아이디들(page)).containsExactly("u249", "u248", "u247");
    }

    @Test
    @DisplayName("userName eq 는 대소문자를 가리지 않는다")
    void userName_eq_는_대소문자를_가리지_않는다() {
        // given
        직원("Kim.Lee", "Kim.Lee", "ext-kim", true);
        직원("park", "park", "ext-park", true);

        // when
        ScimListResponse page = 조회("userName eq \"KIM.LEE\"", 1, 100);

        // then
        assertThat(page.totalResults()).isEqualTo(1);
        assertThat(page.resources().get(0).get("userName").asText()).isEqualTo("Kim.Lee");
    }

    @Test
    @DisplayName("대소문자만 다른 userName 둘이 있으면 둘 다 id 순으로 준다")
    void 대소문자만_다른_둘() {
        // given — GSI 최종 일관성 창이나 이전 데이터로 생길 수 있는 상태
        직원("Kim", "Kim", "e1", true);
        직원("kim", "kim", "e2", true);

        // when
        ScimListResponse page = 조회("userName eq \"kim\"", 1, 100);

        // then
        assertThat(아이디들(page)).containsExactly("Kim", "kim");
    }

    @Test
    @DisplayName("and 뒤의 조건은 찾은 후보 위에서 확인한다")
    void and_뒤의_조건을_확인한다() {
        // given
        직원("kim", "kim", "e1", true);

        // when, then
        assertThat(조회("userName eq \"kim\" and active eq true", 1, 100).totalResults()).isEqualTo(1);
        assertThat(조회("userName eq \"kim\" and active eq false", 1, 100).totalResults()).isZero();
        assertThat(조회("userName eq \"kim\" and displayName eq \"직원 KIM\"", 1, 100).totalResults()).isEqualTo(1);
    }

    @Test
    @DisplayName("externalId 는 대소문자를 가리고, id 로도 찾는다")
    void externalId_와_id() {
        // given
        직원("u1", "u1", "EXT-1", true);

        // when, then
        assertThat(조회("externalId eq \"ext-1\"", 1, 100).totalResults()).isZero();
        assertThat(조회("externalId eq \"EXT-1\"", 1, 100).totalResults()).isEqualTo(1);
        assertThat(조회("id eq \"u1\"", 1, 100).totalResults()).isEqualTo(1);
    }

    @Test
    @DisplayName("인덱스로 찾을 조건이 없거나, 모르는 속성이거나, 값의 타입이 틀리면 invalidFilter 다")
    void 받지_않는_필터() {
        필터_오류(() -> 조회("displayName eq \"x\"", 1, 100));
        필터_오류(() -> 조회("active eq true", 1, 100));
        필터_오류(() -> 조회("nickName eq \"x\"", 1, 100));
        필터_오류(() -> 조회("userName eq \"kim\" and active eq \"true\"", 1, 100));
        필터_오류(() -> 조회("userName eq true", 1, 100));
    }

    @Test
    @DisplayName("속성 선택을 목록의 각 리소스에 적용한다")
    void 속성_선택을_적용한다() {
        // given
        직원("kim", "kim", "e1", true);

        // when
        ScimListResponse page = listing.list(ScimQuery.of(ScimResourceType.USER, "userName eq \"kim\"", 1L, 100L,
                null, null, List.of("userName"), null)).block();

        // then
        JsonNode resource = page.resources().get(0);
        assertThat(resource.has("userName")).isTrue();
        assertThat(resource.has("displayName")).isFalse();
    }
}
```

`connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupListingTest.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimGroupListingTest {

    private FakeStateRepository state;
    private FakeQueryRepository query;
    private ScimGroupListing listing;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        query = new FakeQueryRepository(state);
        listing = new ScimGroupListing(state, query, new FakePageBookmarkRepository());
        state.saveGroup(new DirectoryGroup("DEV001", "grp-dev", "Dev Team",
                Set.of(MemberRef.user("kim"), MemberRef.group("DEV002")))).block();
        state.saveGroup(new DirectoryGroup("DEV002", "grp-dev2", "Backend", Set.of(MemberRef.user("park")))).block();
        state.findGroupCalls.clear();
    }

    private ScimListResponse 조회(String filter, List<String> excluded) {
        return listing.list(ScimQuery.of(ScimResourceType.GROUP, filter, 1L, 100L, null, null, null, excluded)).block();
    }

    @Test
    @DisplayName("excludedAttributes=members 면 조직 파티션을 읽지 않고 members 를 담지 않는다")
    void members_를_빼면_읽지_않는다() {
        // when
        ScimListResponse page = 조회(null, List.of("members"));

        // then
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(page.resources()).allSatisfy(group -> assertThat(group.has("members")).isFalse());
    }

    @Test
    @DisplayName("members 가 응답에 남으면 페이지의 조직만 읽어 멤버를 담는다")
    void members_가_남으면_페이지의_조직을_읽는다() {
        // when
        ScimListResponse page = 조회(null, null);

        // then
        assertThat(state.findGroupCalls).containsExactlyInAnyOrder("DEV001", "DEV002");
        assertThat(page.resources().get(0).get("id").asText()).isEqualTo("DEV002");
        assertThat(page.resources().get(1).get("members")).hasSize(2);
    }

    @Test
    @DisplayName("displayName eq 는 대소문자를 가리지 않고, externalId eq 는 가린다")
    void 조직_필터() {
        // when, then
        assertThat(조회("displayName eq \"dev team\"", List.of("members")).totalResults()).isEqualTo(1);
        assertThat(조회("externalId eq \"grp-dev\"", List.of("members")).totalResults()).isEqualTo(1);
        assertThat(조회("externalId eq \"GRP-DEV\"", List.of("members")).totalResults()).isZero();
    }

    @Test
    @DisplayName("displayName 이 externalId 보다 먼저 인덱스 조건이 된다")
    void 인덱스_조건_우선순위() {
        // when
        조회("externalId eq \"grp-dev\" and displayName eq \"Dev Team\"", List.of("members"));

        // then
        assertThat(query.calls).containsExactly("findGroupHeadersByDisplayName:Dev Team");
    }
}
```

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimUserListingTest' --tests '*ScimGroupListingTest'`
Expected: 컴파일 실패

- [ ] **Step 4: 구현한다**

`connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimListResponse.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.scim.ScimQuery;
import dev.starryeye.organization.scim.ScimSchemas;

import java.util.List;

/**
 * RFC 7644 §3.4.2 ListResponse. {@code Resources} 는 {@code count=0} 이면 없고, 결과가 0건이면 빈 배열이다.
 *
 * @param itemsPerPage 실제로 담은 수
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimListResponse(
        List<String> schemas,
        long totalResults,
        long startIndex,
        int itemsPerPage,
        @JsonProperty("Resources") List<JsonNode> resources
) {

    public static ScimListResponse of(ScimQuery query, long totalResults, List<? extends JsonNode> resources) {
        return new ScimListResponse(List.of(ScimSchemas.LIST_RESPONSE), totalResults, query.startIndex(),
                resources.size(), query.count() == 0 ? null : List.copyOf(resources));
    }
}
```

`ScimMapper` — `toScimGroup(DirectoryGroup)` 뒤에(`import dev.starryeye.organization.core.model.GroupHeader;`):

```java
    /** 멤버 없이 조직을 그린다 — {@code members} 가 응답에 필요 없을 때 멤버 줄을 읽지 않기 위해서다. */
    public static ScimGroup toScimGroup(GroupHeader header) {
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                header.id(),
                header.externalId(),
                header.displayName(),
                null,
                new ScimMeta("Group", "/scim/v2/Groups/" + header.id()));
    }
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPager.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.PageBookmark;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * 페이지 자르기 (S-1 설계 §4.4). 필터 없는 목록은 책갈피로 이어 읽고, 필터 결과는 메모리에서 자른다.
 */
final class ScimPager {

    record Slice<T>(List<T> items, long totalResults) {
    }

    private ScimPager() {
    }

    /**
     * <pre>
     * startIndex=1           → 세기 + 처음부터 count 건 → 책갈피(1+받은 수)
     * startIndex=N, 책갈피 있음 → 책갈피 자리부터 count 건, 전체 수는 책갈피 값 → 책갈피(N+받은 수)
     * startIndex=N, 책갈피 없음 → 세기 + N-1 건 건너뛰기 + count 건 → 책갈피
     * </pre>
     * 다음 위치가 없으면(끝) 책갈피를 남기지 않는다.
     */
    static <T> Mono<Slice<T>> unfiltered(ListingKind kind, ScimQuery query, PageBookmarkRepository bookmarks,
                                         Supplier<Mono<Long>> count,
                                         LongFunction<Mono<String>> skip,
                                         BiFunction<String, Integer, Mono<Page<T>>> list) {
        if (query.count() == 0) {
            return count.get().map(total -> new Slice<>(List.of(), total));
        }
        long start = query.startIndex();
        boolean descending = query.descending();
        Mono<PageBookmark> bookmark = start == 1 ? Mono.empty() : bookmarks.find(kind, descending, start);
        Mono<Tuple2<Optional<String>, Long>> origin = bookmark
                .map(found -> Tuples.of(Optional.of(found.position()), found.totalResults()))
                .switchIfEmpty(Mono.defer(() -> Mono.zip(
                        skip.apply(start - 1).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        count.get())));
        return origin.flatMap(from -> {
            long total = from.getT2();
            return list.apply(from.getT1().orElse(null), query.count())
                    .flatMap(page -> {
                        Slice<T> slice = new Slice<>(page.items(), total);
                        if (!page.hasNext()) {
                            return Mono.just(slice);
                        }
                        long next = start + page.items().size();
                        return bookmarks.save(kind, descending, next, new PageBookmark(page.nextCursor(), total))
                                .thenReturn(slice);
                    });
        });
    }

    /** 이미 정렬된 필터 결과(몇 건)를 자른다. */
    static <T> Slice<T> filtered(List<T> sorted, ScimQuery query) {
        long total = sorted.size();
        if (query.count() == 0 || query.startIndex() > total) {
            return new Slice<>(List.of(), total);
        }
        int from = (int) (query.startIndex() - 1);
        int to = (int) Math.min(total, from + (long) query.count());
        return new Slice<>(sorted.subList(from, to), total);
    }
}
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserListing.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /Users} 의 조회 실행 (S-1 설계 §4.2~4.4).
 *
 * <p>필터가 있으면 인덱스로 찾을 조건 하나({@code id} → {@code userName} → {@code externalId})로 후보를 찾고,
 * {@code and} 로 붙은 나머지 조건은 후보 위에서 확인한다. 전원을 훑는 필터는 받지 않는다.
 */
@RequiredArgsConstructor
public class ScimUserListing {

    /** 필터에 쓸 수 있는 속성과 값의 타입. 이름은 소문자. */
    private static final Map<String, Class<?>> ATTRIBUTES = Map.of(
            "id", String.class,
            "externalid", String.class,
            "username", String.class,
            "displayname", String.class,
            "active", Boolean.class);

    /** 인덱스로 찾을 수 있는 속성, 먼저 고르는 순서대로. */
    private static final List<String> INDEXED = List.of("id", "username", "externalid");

    private final DirectoryStateRepository state;
    private final DirectoryQueryRepository query;
    private final PageBookmarkRepository bookmarks;

    public Mono<ScimListResponse> list(ScimQuery request) {
        Mono<ScimPager.Slice<DirectoryUser>> slice = request.filter() == null
                ? ScimPager.unfiltered(ListingKind.USER, request, bookmarks, query::countUsers,
                        n -> query.skipUsers(n, request.descending()),
                        (from, limit) -> query.listUsers(from, limit, request.descending()))
                : filtered(request);
        return slice.map(page -> ScimListResponse.of(request, page.totalResults(), page.items().stream()
                .map(user -> request.projection().apply(ScimJson.tree(ScimMapper.toScimUser(user))))
                .toList()));
    }

    private Mono<ScimPager.Slice<DirectoryUser>> filtered(ScimQuery request) {
        List<ScimFilter.Term> terms = request.filter().terms();
        terms.forEach(ScimUserListing::check);
        ScimFilter.Term driver = driver(terms);
        return candidates(driver)
                .filter(user -> terms.stream().allMatch(term -> matches(user, term)))
                .collectList()
                .map(users -> ScimPager.filtered(sort(users, request.descending()), request));
    }

    private static void check(ScimFilter.Term term) {
        Class<?> type = ATTRIBUTES.get(term.attribute());
        if (type == null) {
            throw ScimException.invalidFilter("필터할 수 없는 속성입니다: " + term.attribute());
        }
        if (!type.isInstance(term.value())) {
            throw ScimException.invalidFilter("속성 '" + term.attribute() + "' 에 맞지 않는 값입니다: " + term.value());
        }
    }

    private static ScimFilter.Term driver(List<ScimFilter.Term> terms) {
        for (String attribute : INDEXED) {
            for (ScimFilter.Term term : terms) {
                if (term.attribute().equals(attribute)) {
                    return term;
                }
            }
        }
        throw ScimException.invalidFilter(
                "id·userName·externalId 중 하나의 eq 가 있어야 합니다 — 전원을 훑는 필터는 받지 않습니다");
    }

    private Flux<DirectoryUser> candidates(ScimFilter.Term driver) {
        String value = (String) driver.value();
        return switch (driver.attribute()) {
            case "id" -> state.findUser(value).flux();
            case "username" -> query.findUsersByUserName(value);
            default -> query.findUsersByExternalId(value);
        };
    }

    private static boolean matches(DirectoryUser user, ScimFilter.Term term) {
        return switch (term.attribute()) {
            case "id" -> term.value().equals(user.id());
            case "externalid" -> term.value().equals(user.externalId());
            case "username" -> ScimText.sameIgnoringCase(user.userName(), (String) term.value());
            case "displayname" -> ScimText.sameIgnoringCase(user.displayName(), (String) term.value());
            case "active" -> term.value().equals(user.active());
            default -> false;
        };
    }

    /** 인덱스와 같은 순서 — userName 소문자, 같으면 id. */
    private static List<DirectoryUser> sort(List<DirectoryUser> users, boolean descending) {
        Comparator<DirectoryUser> order = Comparator
                .comparing((DirectoryUser user) -> ScimText.lower(user.userName() == null ? user.id() : user.userName()))
                .thenComparing(DirectoryUser::id);
        return users.stream().sorted(descending ? order.reversed() : order).toList();
    }
}
```

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupListing.java`:

```java
package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /Groups} 의 조회 실행 (S-1 설계 §4.2~4.5).
 *
 * <p>조직은 이름표(META)로 찾고 자른다. <b>{@code members} 가 응답에 남을 때만</b> 페이지에 든 조직의
 * 파티션을 읽는다 — Entra 는 조직을 조회할 때마다 {@code excludedAttributes=members} 를 붙인다.
 */
@RequiredArgsConstructor
public class ScimGroupListing {

    private static final Set<String> ATTRIBUTES = Set.of("id", "externalid", "displayname");
    private static final List<String> INDEXED = List.of("id", "displayname", "externalid");
    /** 페이지(최대 100개)의 조직 파티션을 읽는 동시성. 저장소의 다른 읽기와 같은 값이다. */
    private static final int MEMBER_READ_CONCURRENCY = 8;

    private final DirectoryStateRepository state;
    private final DirectoryQueryRepository query;
    private final PageBookmarkRepository bookmarks;

    public Mono<ScimListResponse> list(ScimQuery request) {
        Mono<ScimPager.Slice<GroupHeader>> slice = request.filter() == null
                ? ScimPager.unfiltered(ListingKind.GROUP, request, bookmarks, query::countGroups,
                        n -> query.skipGroups(n, request.descending()),
                        (from, limit) -> query.listGroupHeaders(from, limit, request.descending()))
                : filtered(request);
        return slice.flatMap(page -> resources(page.items(), request)
                .map(resources -> ScimListResponse.of(request, page.totalResults(), resources)));
    }

    private Mono<List<ObjectNode>> resources(List<GroupHeader> headers, ScimQuery request) {
        if (!request.projection().includes("members")) {
            return Mono.just(headers.stream()
                    .map(header -> request.projection().apply(ScimJson.tree(ScimMapper.toScimGroup(header))))
                    .toList());
        }
        // 페이지 순서를 지킨다. 그사이 지워진 조직은 빈 결과라 빠진다(itemsPerPage 가 실제 수를 말한다)
        return Flux.fromIterable(headers)
                .flatMapSequential(header -> state.findGroup(header.id()), MEMBER_READ_CONCURRENCY)
                .map(group -> request.projection().apply(ScimJson.tree(ScimMapper.toScimGroup(group))))
                .collectList();
    }

    private Mono<ScimPager.Slice<GroupHeader>> filtered(ScimQuery request) {
        List<ScimFilter.Term> terms = request.filter().terms();
        terms.forEach(ScimGroupListing::check);
        ScimFilter.Term driver = driver(terms);
        return candidates(driver)
                .filter(group -> terms.stream().allMatch(term -> matches(group, term)))
                .collectList()
                .map(groups -> ScimPager.filtered(sort(groups, request.descending()), request));
    }

    private static void check(ScimFilter.Term term) {
        if (!ATTRIBUTES.contains(term.attribute())) {
            throw ScimException.invalidFilter("필터할 수 없는 속성입니다: " + term.attribute());
        }
        if (!(term.value() instanceof String)) {
            throw ScimException.invalidFilter("속성 '" + term.attribute() + "' 에 맞지 않는 값입니다: " + term.value());
        }
    }

    private static ScimFilter.Term driver(List<ScimFilter.Term> terms) {
        for (String attribute : INDEXED) {
            for (ScimFilter.Term term : terms) {
                if (term.attribute().equals(attribute)) {
                    return term;
                }
            }
        }
        throw ScimException.invalidFilter("id·displayName·externalId 중 하나의 eq 가 있어야 합니다");
    }

    private Flux<GroupHeader> candidates(ScimFilter.Term driver) {
        String value = (String) driver.value();
        return switch (driver.attribute()) {
            case "id" -> state.findGroupHeader(value).flux();
            case "displayname" -> query.findGroupHeadersByDisplayName(value);
            default -> query.findGroupHeadersByExternalId(value);
        };
    }

    private static boolean matches(GroupHeader group, ScimFilter.Term term) {
        return switch (term.attribute()) {
            case "id" -> term.value().equals(group.id());
            case "externalid" -> term.value().equals(group.externalId());
            case "displayname" -> ScimText.sameIgnoringCase(group.displayName(), (String) term.value());
            default -> false;
        };
    }

    private static List<GroupHeader> sort(List<GroupHeader> groups, boolean descending) {
        Comparator<GroupHeader> order = Comparator
                .comparing((GroupHeader group) -> ScimText.lower(group.displayName() == null ? group.id() : group.displayName()))
                .thenComparing(GroupHeader::id);
        return groups.stream().sorted(descending ? order.reversed() : order).toList();
    }
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: 전부 PASS

- [ ] **Step 6: 커밋하고 푸시한다**

```bash
git add core/src/testFixtures connector-scim
git commit -m "feat: SCIM 목록 조회 엔진 — 필터는 인덱스 조건 하나로, 목록은 책갈피로 이어 읽는다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 7: HTTP — 목록·.search 라우트와 모든 응답의 속성 선택

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimListHandler.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java`
- Modify: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java`, `ScimGroupHandlerTest.java` (`setUp` 만)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimListHandlerTest.java` (신규)

**Interfaces:**
- Consumes: `ScimUserListing`·`ScimGroupListing` (Task 6), `ScimQuery`·`ScimAttributeProjection`·`ScimJson` (Task 5), `ScimException.notImplemented` (Task 4)
- Produces: `ScimListHandler(ScimUserListing users, ScimGroupListing groups)` — `listUsers`, `searchUsers`, `listGroups`, `searchGroups` (`ServerRequest → Mono<ServerResponse>`)
- Produces: `ScimRouter.scimRoutes(ScimUserHandler users, ScimGroupHandler groups, ScimListHandler lists)` — 인자가 셋이 된다

- [ ] **Step 1: 기존 핸들러 테스트의 setUp 을 새 라우터에 맞춘다**

`ScimUserHandlerTest.setUp` 과 `ScimGroupHandlerTest.setUp` 의 `WebTestClient.bindToRouterFunction(...)` 을 다음으로 바꾼다(두 파일 같다, `FakeQueryRepository`·`FakePageBookmarkRepository` import):

```java
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(state, query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`connector-scim/src/test/java/dev/starryeye/organization/scim/ScimListHandlerTest.java` — `@BeforeEach void setUp()` **하나**에서 `ScimUserHandlerTest` 와 같은 가짜·유스케이스를 만들고(그 파일의 import 를 그대로 가져온다) 위 Step 1 의 `bindToRouterFunction` 으로 `client` 를 만든 뒤, **같은 메서드 끝에** 아래 데이터를 둔다(`@BeforeEach` 를 둘로 나누면 JUnit 이 순서를 보장하지 않는다). 필드는 `state`(`FakeStateRepository`)와 `client`(`WebTestClient`).

```java
        // setUp 의 끝 — client 를 만든 다음
        state.saveUser(new DirectoryUser("Kim.Lee", "ext-kim", "Kim.Lee", "이김", "kim@example.com", true)).block();
        state.saveUser(new DirectoryUser("park", "ext-park", "park", "박", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "grp-dev", "Dev Team", Set.of(MemberRef.user("Kim.Lee")))).block();
        state.findGroupCalls.clear();
```

테스트:

```java
    @Test
    @DisplayName("Okta 의 필터 조회에 ListResponse 로 답한다")
    void Okta_필터_조회() {
        client.get().uri("/scim/v2/Users?filter={f}&startIndex=1&count=100", "userName eq \"kim.lee\"")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.LIST_RESPONSE)
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.startIndex").isEqualTo(1)
                .jsonPath("$.itemsPerPage").isEqualTo(1)
                .jsonPath("$.Resources[0].userName").isEqualTo("Kim.Lee");
    }

    @Test
    @DisplayName("결과가 없으면 빈 Resources, count=0 이면 Resources 없이 전체 수만 준다")
    void 결과_없음과_count_0() {
        client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"nobody\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(0)
                .jsonPath("$.Resources").isArray()
                .jsonPath("$.Resources").isEmpty();

        client.get().uri("/scim/v2/Users?count=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources").doesNotExist();
    }

    @Test
    @DisplayName("Entra 의 조직 조회 — excludedAttributes=members 면 멤버를 읽지도 담지도 않는다")
    void Entra_조직_조회() {
        client.get().uri("/scim/v2/Groups?excludedAttributes=members&filter={f}", "displayName eq \"Dev Team\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].id").isEqualTo("DEV001")
                .jsonPath("$.Resources[0].members").doesNotExist();

        client.get().uri("/scim/v2/Groups/DEV001?excludedAttributes=members")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("DEV001")
                .jsonPath("$.members").doesNotExist();

        assertThat(state.findGroupCalls).isEmpty();
    }

    @Test
    @DisplayName("단건 GET 과 쓰기 응답에도 attributes 를 적용한다")
    void 단건과_쓰기_응답의_속성_선택() {
        client.get().uri("/scim/v2/Users/park?attributes=userName")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("park")
                .jsonPath("$.userName").isEqualTo("park")
                .jsonPath("$.displayName").doesNotExist();

        client.post().uri("/scim/v2/Users?attributes=id")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"choi","active":true}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("choi")
                .jsonPath("$.userName").doesNotExist();
    }

    @Test
    @DisplayName("잘못된 속성 선택은 쓰기 전에 거절한다 — 상태가 바뀌지 않는다")
    void 잘못된_속성_선택은_쓰기_전에_거절한다() {
        client.post().uri("/scim/v2/Users?attributes=nickName")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"choi","active":true}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");

        assertThat(state.users).doesNotContainKey("choi");
    }

    @Test
    @DisplayName("받지 않는 필터·정렬·페이지 값은 400 과 scimType 으로 답한다")
    void 오류_응답() {
        client.get().uri("/scim/v2/Users?filter={f}", "userName co \"k\"")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidFilter");
        client.get().uri("/scim/v2/Users?sortBy=displayName")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidValue");
        client.get().uri("/scim/v2/Users?startIndex=abc")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidValue");
        client.get().uri("/scim/v2/Users?attributes=userName&excludedAttributes=emails")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidValue");
    }

    @Test
    @DisplayName(".search 는 본문의 조회를 같은 엔진으로 실행한다 — SearchRequest 스키마가 없으면 invalidSyntax")
    void search() {
        client.post().uri("/scim/v2/Users/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"],
                         "filter":"userName eq \\"park\\"","attributes":["userName"],"startIndex":1,"count":10}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.Resources[0].userName").isEqualTo("park")
                .jsonPath("$.Resources[0].displayName").doesNotExist();

        client.post().uri("/scim/v2/Groups/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"filter\":\"displayName eq \\\"Dev Team\\\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidSyntax");
    }

    @Test
    @DisplayName("서버 루트 조회는 501 이다")
    void 서버_루트_조회는_501() {
        client.get().uri("/scim/v2?filter={f}", "userName eq \"park\"")
                .exchange().expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        client.post().uri("/scim/v2/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"]}")
                .exchange().expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    @DisplayName("ServiceProviderConfig 가 필터와 정렬 지원을 선언한다")
    void ServiceProviderConfig() {
        client.get().uri("/scim/v2/ServiceProviderConfig")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.filter.supported").isEqualTo(true)
                .jsonPath("$.filter.maxResults").isEqualTo(100)
                .jsonPath("$.sort.supported").isEqualTo(true);
    }
```

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimListHandlerTest'`
Expected: 컴파일 실패(`ScimListHandler`, 3인자 `scimRoutes` 없음)

- [ ] **Step 4: 구현한다**

`connector-scim/src/main/java/dev/starryeye/organization/scim/ScimListHandler.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimListResponse;
import dev.starryeye.organization.scim.dto.ScimSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static dev.starryeye.organization.scim.ScimRouter.SCIM_JSON;

/**
 * 목록·{@code .search} 조회 (RFC 7644 §3.4.2, §3.4.3). 파라미터 검사에서 나는 예외는
 * {@code Mono.fromCallable} 로 {@code onError} 가 되어 {@link ScimRouter} 의 오류 번역을 탄다.
 */
@RequiredArgsConstructor
public class ScimListHandler {

    private final ScimUserListing users;
    private final ScimGroupListing groups;

    public Mono<ServerResponse> listUsers(ServerRequest request) {
        return Mono.fromCallable(() -> ScimQuery.fromRequest(ScimResourceType.USER, request))
                .flatMap(users::list)
                .flatMap(ScimListHandler::ok);
    }

    public Mono<ServerResponse> searchUsers(ServerRequest request) {
        return body(request)
                .map(search -> ScimQuery.fromSearch(ScimResourceType.USER, search))
                .flatMap(users::list)
                .flatMap(ScimListHandler::ok);
    }

    public Mono<ServerResponse> listGroups(ServerRequest request) {
        return Mono.fromCallable(() -> ScimQuery.fromRequest(ScimResourceType.GROUP, request))
                .flatMap(groups::list)
                .flatMap(ScimListHandler::ok);
    }

    public Mono<ServerResponse> searchGroups(ServerRequest request) {
        return body(request)
                .map(search -> ScimQuery.fromSearch(ScimResourceType.GROUP, search))
                .flatMap(groups::list)
                .flatMap(ScimListHandler::ok);
    }

    private static Mono<ScimSearchRequest> body(ServerRequest request) {
        return request.bodyToMono(ScimSearchRequest.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")));
    }

    private static Mono<ServerResponse> ok(ScimListResponse body) {
        return ServerResponse.ok().contentType(SCIM_JSON).bodyValue(body);
    }
}
```

`ScimRouter.scimRoutes` — 시그니처와 라우트:

```java
    public static RouterFunction<ServerResponse> scimRoutes(ScimUserHandler users,
                                                            ScimGroupHandler groups,
                                                            ScimListHandler lists) {
        return RouterFunctions.route()
                .GET("/scim/v2/Users", lists::listUsers)
                .POST("/scim/v2/Users/.search", lists::searchUsers)
                .POST("/scim/v2/Users", users::create)
                .GET("/scim/v2/Users/{id}", users::get)
                .PUT("/scim/v2/Users/{id}", users::replace)
                .PATCH("/scim/v2/Users/{id}", users::patch)
                .DELETE("/scim/v2/Users/{id}", users::delete)
                .GET("/scim/v2/Groups", lists::listGroups)
                .POST("/scim/v2/Groups/.search", lists::searchGroups)
                .POST("/scim/v2/Groups", groups::create)
                .GET("/scim/v2/Groups/{id}", groups::get)
                .PUT("/scim/v2/Groups/{id}", groups::replace)
                .PATCH("/scim/v2/Groups/{id}", groups::patch)
                .DELETE("/scim/v2/Groups/{id}", groups::delete)
                .GET("/scim/v2/ServiceProviderConfig", request -> serviceProviderConfig())
                // 서버 루트 조회(여러 리소스 종류를 한꺼번에)는 지원하지 않는다 — S-1 설계 §4.6
                .GET("/scim/v2", request -> rootQuery())
                .GET("/scim/v2/", request -> rootQuery())
                .POST("/scim/v2/.search", request -> rootQuery())
                .onError(Throwable.class, ScimRouter::toScimError)
                .build();
    }

    private static Mono<ServerResponse> rootQuery() {
        return Mono.error(ScimException.notImplemented(
                "서버 루트 조회는 지원하지 않습니다 — /scim/v2/Users 나 /scim/v2/Groups 로 조회하세요"));
    }
```

`serviceProviderConfig` 의 자바독과 두 줄:

```java
    /**
     * 지원하는 기능을 정직하게 선언한다. 필터는 {@code eq}·{@code and} 만 받고 나머지는 {@code invalidFilter}
     * 다 — RFC 7644 는 필터 지원 여부만 선언하게 하고 부분 지원을 400 으로 알리게 한다(S-1 설계 §4.1).
     * 정렬은 인덱스 키({@code userName}/{@code displayName})로만 한다.
     */
```

```java
                "filter", Map.of("supported", true, "maxResults", ScimQuery.MAX_COUNT),
                ...
                "sort", Map.of("supported", true),
```

`ScimUserHandler` — 속성 선택을 모든 응답에 적용한다. 선택은 **쓰기 전에** 검사한다(잘못된 파라미터로 상태가 바뀌지 않게). `import com.fasterxml.jackson.databind.node.ObjectNode;` 는 필요 없다(`ScimJson.tree` 결과를 바로 `apply` 한다):

```java
    public Mono<ServerResponse> create(ServerRequest request) {
        return projection(request).flatMap(projection -> request.bodyToMono(ScimUser.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .map(ScimMapper::toDirectoryUser)
                .flatMap(this::rejectDuplicate)
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.CREATED, user.id(), result, projection))));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .flatMap(user -> ServerResponse.ok().contentType(SCIM_JSON)
                        .bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(user))))));
    }
```

```java
    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .then(request.bodyToMono(ScimUser.class)
                        .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다"))))
                .map(ScimMapper::toDirectoryUser)
                // PUT 은 경로의 id 를 정본으로 삼는다. 본문의 userName 이 달라도 리소스를 옮기지 않는다.
                .map(user -> new DirectoryUser(id, user.externalId(), user.userName(),
                        user.displayName(), user.email(), user.active()))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.OK, id, result, projection))));
    }

    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .zipWith(request.bodyToMono(ScimPatchOp.class)
                        .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다"))))
                .map(both -> ScimPatchApplier.applyToUser(both.getT1(), both.getT2()))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.OK, id, result, projection))));
    }
```

`respond` 와 새 도우미:

```java
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result,
                                         ScimAttributeProjection projection) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.internal(
                    "일부 튜플 적용에 실패했습니다. 재시도해 주세요: " + id));
        }
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.internal("저장된 리소스를 다시 읽지 못했습니다: " + id)))
                .flatMap(saved -> ServerResponse.status(status)
                        .contentType(SCIM_JSON)
                        .bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(saved)))));
    }

    /** 응답에 담을 속성(RFC 7644 §3.9). 쓰기 전에 검사해 잘못된 파라미터로 상태가 바뀌지 않게 한다. */
    private static Mono<ScimAttributeProjection> projection(ServerRequest request) {
        return Mono.fromCallable(() -> ScimAttributeProjection.fromRequest(ScimResourceType.USER, request));
    }
```

`ScimGroupHandler` — `create`·`replace`·`patch` 를 같은 방식으로 감싼다:

```java
    public Mono<ServerResponse> create(ServerRequest request) {
        return projection(request).flatMap(projection -> request.bodyToMono(ScimGroup.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(scim -> ScimMapper.toDirectoryGroup(scim, memberTypes))
                .flatMap(group -> state.findGroup(group.id())
                        .flatMap(existing -> Mono.<DirectoryGroup>error(ScimException.uniqueness(
                                "이미 존재하는 조직입니다: " + group.id())))
                        .switchIfEmpty(Mono.just(group)))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.CREATED, group.id(), result, projection))));
    }

    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .then(request.bodyToMono(ScimGroup.class)
                        .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다"))))
                .flatMap(scim -> ScimMapper.toDirectoryGroup(scim, memberTypes))
                // 경로의 조직코드가 정본이다. 본문의 externalId 가 달라도 리소스를 옮기지 않는다.
                .map(group -> new DirectoryGroup(id, group.externalId(),
                        group.displayName(), group.members()))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.OK, id, result, projection))));
    }

    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .zipWith(request.bodyToMono(ScimPatchOp.class)
                        .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다"))))
                .flatMap(both -> ScimPatchApplier.applyToGroup(both.getT1(), both.getT2(), memberTypes))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.OK, id, result, projection))));
    }
```

`get` 은 `members` 가 필요 없으면 이름표만 읽는다:

```java
    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> {
            // members 가 응답에 없으면 조직 파티션(멤버 줄 전부)을 읽지 않는다 — Entra 가 늘 붙이는 조건이다
            Mono<ScimGroup> group = projection.includes("members")
                    ? state.findGroup(id).map(ScimMapper::toScimGroup)
                    : state.findGroupHeader(id).map(ScimMapper::toScimGroup);
            return group
                    .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                    .flatMap(scim -> ServerResponse.ok().contentType(SCIM_JSON)
                            .bodyValue(projection.apply(ScimJson.tree(scim))));
        });
    }
```

```java
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result,
                                         ScimAttributeProjection projection) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.internal(
                    "일부 튜플 적용에 실패했습니다. 재시도해 주세요: " + id));
        }
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.internal("저장된 리소스를 다시 읽지 못했습니다: " + id)))
                .flatMap(saved -> ServerResponse.status(status)
                        .contentType(SCIM_JSON)
                        .bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimGroup(saved)))));
    }

    /** 응답에 담을 속성(RFC 7644 §3.9). 쓰기 전에 검사해 잘못된 파라미터로 상태가 바뀌지 않게 한다. */
    private static Mono<ScimAttributeProjection> projection(ServerRequest request) {
        return Mono.fromCallable(() -> ScimAttributeProjection.fromRequest(ScimResourceType.GROUP, request));
    }
```

(`ScimGroup` import 가 이미 없으면 `dev.starryeye.organization.scim.dto.ScimGroup` 을 더한다.)

`ScimConfig` — 빈 셋을 더하고 라우터에 넘긴다(`DirectoryQueryRepository`, `PageBookmarkRepository` import):

```java
    @Bean
    public ScimUserListing scimUserListing(DirectoryStateRepository state, DirectoryQueryRepository query,
                                           PageBookmarkRepository bookmarks) {
        return new ScimUserListing(state, query, bookmarks);
    }

    @Bean
    public ScimGroupListing scimGroupListing(DirectoryStateRepository state, DirectoryQueryRepository query,
                                             PageBookmarkRepository bookmarks) {
        return new ScimGroupListing(state, query, bookmarks);
    }

    @Bean
    public ScimListHandler scimListHandler(ScimUserListing users, ScimGroupListing groups) {
        return new ScimListHandler(users, groups);
    }

    @Bean
    public RouterFunction<ServerResponse> scimRouterFunction(ScimUserHandler users, ScimGroupHandler groups,
                                                             ScimListHandler lists) {
        return ScimRouter.scimRoutes(users, groups, lists);
    }
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: 전부 PASS (기존 핸들러 테스트 포함)

- [ ] **Step 6: 커밋하고 푸시한다**

```bash
git add connector-scim
git commit -m "feat: GET /Users·/Groups 목록과 .search, 모든 응답에 속성 선택, 루트 조회 501" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 8: E2E 와 README

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimQueryEndToEndTest.java`
- Modify: `README.md` (SCIM 절, 테이블 재생성 문단)

**Interfaces:**
- Consumes: 앱 전체(Task 1~7). 실제 DynamoDB Local·OpenFGA 컨테이너.

- [ ] **Step 1: E2E 테스트를 쓴다**

`app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimQueryEndToEndTest.java` — 클래스 선언부(애노테이션 네 개, `OPENFGA`·`DYNAMODB` 컨테이너, `@DynamicPropertySource`)는 `ScimEndToEndTest` 의 것을 그대로 옮긴다. 필드는 `@Autowired WebTestClient client;` 하나. 본문:

```java
    private void 생성한다(String uri, String body) {
        client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @Order(1)
    @DisplayName("직원 둘과 조직 하나를 SCIM 으로 만든다")
    void 준비한다() {
        생성한다("/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"ext-kim","userName":"Kim.Lee","displayName":"이김","active":true}
                """);
        생성한다("/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"ext-park","userName":"park","displayName":"박","active":true}
                """);
        생성한다("/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"grp-dev","displayName":"Dev Team",
                 "members":[{"value":"Kim.Lee","type":"User"}]}
                """);
    }

    @Test
    @Order(2)
    @DisplayName("Okta — userName 필터는 대소문자를 가리지 않고 ListResponse 로 답한다")
    void Okta_userName_필터() {
        client.get().uri("/scim/v2/Users?filter={f}&startIndex=1&count=100", "userName eq \"kim.lee\"")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/scim+json")
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:ListResponse")
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.Resources[0].userName").isEqualTo("Kim.Lee");
    }

    @Test
    @Order(3)
    @DisplayName("Okta — 필터 없는 목록은 userName 소문자 순이다")
    void Okta_목록() {
        client.get().uri("/scim/v2/Users?startIndex=1&count=100")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].id").isEqualTo("Kim.Lee")
                .jsonPath("$.Resources[1].id").isEqualTo("park");
    }

    @Test
    @Order(4)
    @DisplayName("Okta — 조직명 필터는 멤버까지 담는다")
    void Okta_조직_필터() {
        client.get().uri("/scim/v2/Groups?filter={f}&startIndex=1&count=100", "displayName eq \"dev team\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.Resources[0].members[0].value").isEqualTo("Kim.Lee");
    }

    @Test
    @Order(5)
    @DisplayName("Entra — externalId 필터는 대소문자를 가린다")
    void Entra_externalId() {
        client.get().uri("/scim/v2/Users?filter={f}", "externalId eq \"ext-kim\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(1);
        client.get().uri("/scim/v2/Users?filter={f}", "externalId eq \"EXT-KIM\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(0);
    }

    @Test
    @Order(6)
    @DisplayName("Entra — excludedAttributes=members 면 조직 목록과 단건 모두 멤버를 담지 않는다")
    void Entra_멤버_제외() {
        client.get().uri("/scim/v2/Groups?excludedAttributes=members&filter={f}", "displayName eq \"Dev Team\"")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].id").isEqualTo("grp-dev")
                .jsonPath("$.Resources[0].members").doesNotExist();
        client.get().uri("/scim/v2/Groups/grp-dev?excludedAttributes=members")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("grp-dev")
                .jsonPath("$.members").doesNotExist();
    }

    @Test
    @Order(7)
    @DisplayName("대소문자만 다른 userName 으로 만들면 409 다")
    void 대소문자만_다른_userName_은_409() {
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"KIM.LEE","active":true}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");
    }

    @Test
    @Order(8)
    @DisplayName(".search 는 본문의 조회를 실행한다")
    void search() {
        client.post().uri("/scim/v2/Users/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"],
                         "filter":"userName eq \\"park\\"","attributes":["userName"]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].userName").isEqualTo("park")
                .jsonPath("$.Resources[0].displayName").doesNotExist();
    }

    @Test
    @Order(9)
    @DisplayName("한 명씩 페이지를 넘기면 실제 저장소의 책갈피로 이어 읽어 각자 한 번씩 나온다")
    void 책갈피로_이어_읽는다() {
        client.get().uri("/scim/v2/Users?startIndex=1&count=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].id").isEqualTo("Kim.Lee");
        client.get().uri("/scim/v2/Users?startIndex=2&count=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].id").isEqualTo("park");
    }

    @Test
    @Order(10)
    @DisplayName("받지 않는 필터는 invalidFilter 이고, ServiceProviderConfig 는 필터 지원을 선언한다")
    void 필터_오류와_선언() {
        client.get().uri("/scim/v2/Users?filter={f}", "userName co \"k\"")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidFilter");
        client.get().uri("/scim/v2/ServiceProviderConfig")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.filter.supported").isEqualTo(true);
    }
```

- [ ] **Step 2: 실행한다**

Run: `./gradlew :app-scim:test --tests '*ScimQueryEndToEndTest'`
Expected: PASS (Task 1~7 이 이미 구현돼 있으므로 처음부터 통과해야 한다. 실패하면 앱 배선 — `ScimConfig` 의 새 빈, `DynamoDbConfig` 의 새 저장소 빈 — 부터 본다)

- [ ] **Step 3: README 를 고친다**

지원 엔드포인트 표를:

```markdown
| 리소스 | POST | GET (단건) | GET (목록·필터) | PUT | PATCH | DELETE |
|---|---|---|---|---|---|---|
| `/scim/v2/Users` | O | O | O | O | O | O |
| `/scim/v2/Groups` | O | O | O | O | O | O |
| `/scim/v2/ServiceProviderConfig` | - | O | - | - | - | - |
```

로 바꾸고, `**목록 조회(\`GET /Users\`, \`GET /Groups\`)와 필터는 지원하지 않는다.**` 줄을 다음으로 바꾼다:

```markdown
목록·필터 조회(RFC 7644 §3.4.2)와, 같은 조회를 본문으로 보내는 `POST /scim/v2/Users/.search`·`/Groups/.search` 를
지원한다.

| | 지원 범위 |
|---|---|
| `filter` | `eq` 와 `and` 만. 직원은 `id`·`userName`·`externalId`, 조직은 `id`·`displayName`·`externalId` 중 하나의 `eq` 가 있어야 한다(`and` 뒤에는 직원 `displayName`·`active` 도 온다). 그 밖은 400 `invalidFilter` |
| 대소문자 | `userName`·조직 `displayName` 은 가리지 않는다(RFC 7643 `caseExact=false`) — `Kim` 이 있으면 `kim` 생성은 409 다. `id`·`externalId` 는 가린다 |
| `startIndex`·`count` | `count` 기본값·상한 100 |
| `sortBy`·`sortOrder` | 직원 `userName`, 조직 `displayName` 만 |
| `attributes`·`excludedAttributes` | 리소스를 돌려주는 모든 응답. 조직에서 `members` 를 빼면 멤버를 읽지 않는다 |

필터 없는 목록은 IdP 가 페이지를 순서대로 부른다는 점을 이용해, 다음 페이지를 이어 읽을 위치를 DynamoDB 에 15분
동안 책갈피로 둔다 — 직원이 10만 명이어도 페이지마다 100건만 읽는다. `totalResults` 는 가져오기 첫 페이지에서 센
값이다. 서버 루트 조회(`GET /scim/v2?filter=`)는 501 이다. 설계: `docs/superpowers/specs/2026-09-25-scim-list-filter-design.md`.
```

"이 브랜치는 키 레이아웃을 바꾼다" 문단 뒤에 추가:

```markdown
**S-1(SCIM 목록·필터)도 키를 바꾼다** — GSI1 정렬키가 소문자가 되고(`userName`·조직명을 대소문자 없이 찾기 위해)
`externalId` 로 찾는 GSI3 와 책갈피 만료(TTL)가 생긴다. 기존 테이블은 다시 만들어야 한다.
```

- [ ] **Step 4: 커밋하고 푸시한다**

```bash
git add app-scim/src/test README.md
git commit -m "test: SCIM 목록·필터 E2E — Okta·Entra 요청을 그대로, 문서에 지원 범위" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 9: 10만 명 목록 규모 테스트

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/DynamoDbReadCounter.java`
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimListingScaleTest.java`
- Modify: `docs/superpowers/specs/2026-09-25-scim-list-filter-design.md` §11 (컨트롤러가 결과를 적는다)

**Interfaces:**
- Consumes: 앱 전체, `OrgChartFixture.오천명()`, `ScaleContainers`, `@ScaleTest`, `DirectoryQueryRepository.countUsers()`

**이 과제의 규모 테스트는 서브에이전트가 돌리지 않는다.** 구현자는 `./gradlew :app-scim:compileTestJava` 까지만 확인하고, 실행과 §11 기록은 컨트롤러가 한다(Global Constraints 의 Gradle 규칙).

- [ ] **Step 1: 읽기 계측을 만든다**

`app-scim/src/test/java/dev/starryeye/organization/scim/app/DynamoDbReadCounter.java`:

```java
package dev.starryeye.organization.scim.app;

import org.springframework.beans.factory.config.BeanPostProcessor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DynamoDB 클라이언트 빈을 감싸 Query 가 훑은 아이템 수와 GetItem 횟수를 센다 (S-1 설계 §8.4).
 *
 * <p>성능 주장을 시간이 아니라 <b>읽은 양</b>으로 단정하기 위한 계측이다 — DynamoDB Local 의 속도는 AWS 와
 * 달라 시간으로는 아무것도 증명하지 못한다. {@code @Import} 로 테스트 컨텍스트에만 들어간다.
 */
class DynamoDbReadCounter implements BeanPostProcessor {

    final AtomicLong queries = new AtomicLong();
    final AtomicLong scannedItems = new AtomicLong();
    final AtomicLong getItems = new AtomicLong();

    void reset() {
        queries.set(0);
        scannedItems.set(0);
        getItems.set(0);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DynamoDbAsyncClient client)) {
            return bean;
        }
        return Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(client, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("query") && result instanceof CompletableFuture<?> future) {
                        return future.thenApply(response -> {
                            queries.incrementAndGet();
                            scannedItems.addAndGet(((QueryResponse) response).scannedCount());
                            return response;
                        });
                    }
                    if (method.getName().equals("getItem")) {
                        getItems.incrementAndGet();
                    }
                    return result;
                });
    }
}
```

- [ ] **Step 2: 규모 테스트를 쓴다**

`app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimListingScaleTest.java`:

```java
package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 직원 10만 명에서의 SCIM 목록·필터 조회 (S-1 설계 §8.4).
 *
 * <p>실제 운영 규모가 10만 명 이상이라 5천 명 픽스처로는 목록 방식의 비용을 말할 수 없다. 목록은 읽기만
 * 검증하므로 SCIM API 를 거치지 않고 저장소에 직접 심는다 — 5천 명 조직도(조직·멤버십 포함)에 직원만 9만 5천
 * 명을 더한다. 비용은 {@link DynamoDbReadCounter} 로 <b>읽은 아이템 수</b>를 단정한다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DynamoDbReadCounter.class)
@ScaleTest
class ScimListingScaleTest {

    private static final OrgChart 조직도 = OrgChartFixture.오천명();
    private static final int 전체 = 100_000;
    private static final int 추가 = 전체 - 조직도.snapshot().users().size();

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired DirectoryQueryRepository query;
    @Autowired DynamoDbReadCounter counter;

    private static String 추가아이디(int i) {
        return "extra-%06d".formatted(i);
    }

    private JsonNode 조회한다(String uriTemplate, Object... values) {
        return client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .get().uri(uriTemplate, values).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
    }

    @Test
    @Order(1)
    @DisplayName("직원 10만 명과 5천 명 조직도의 조직을 저장소에 직접 심는다")
    void 심는다() {
        // given
        long 시작 = System.currentTimeMillis();

        // when
        state.replaceWith(조직도.snapshot()).block(Duration.ofMinutes(15));
        Flux.range(0, 추가)
                .map(i -> new DirectoryUser(추가아이디(i), "ext-" + 추가아이디(i), 추가아이디(i),
                        "추가 직원 " + i, null, true))
                .flatMap(state::saveUser, 64)
                .blockLast(Duration.ofMinutes(30));

        // then
        assertThat(query.countUsers().block()).isEqualTo((long) 전체);
        System.out.printf("심기: %,d명, %,dms%n", 전체, System.currentTimeMillis() - 시작);
    }

    @Test
    @Order(2)
    @DisplayName("Okta 처럼 100명씩 끝까지 가져오면 전원이 한 번씩 나오고, 읽는 양은 인원에 선형이다")
    void 전원을_가져온다() {
        // given
        counter.reset();
        Set<String> 본것 = new HashSet<>();
        long totalResults = -1;
        long 시작 = System.currentTimeMillis();

        // when
        for (long startIndex = 1; totalResults < 0 || startIndex <= totalResults; startIndex += 100) {
            JsonNode page = 조회한다("/scim/v2/Users?startIndex={s}&count=100", startIndex);
            totalResults = page.get("totalResults").asLong();
            page.get("Resources").forEach(user -> assertThat(본것.add(user.get("id").asText())).isTrue());
        }

        // then — 첫 페이지의 세기가 N, 페이지들이 N. 다 읽고 자르기였다면 N²/100 = 1억이다
        assertThat(totalResults).isEqualTo(전체);
        assertThat(본것).hasSize(전체);
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(2L * 전체 + 1_000);
        System.out.printf("가져오기: %,dms, Query %,d번, 훑은 아이템 %,d, GetItem %,d번%n",
                System.currentTimeMillis() - 시작, counter.queries.get(),
                counter.scannedItems.get(), counter.getItems.get());
    }

    @Test
    @Order(3)
    @DisplayName("조직을 멤버까지 끝까지 가져오면 조직도와 같다")
    void 조직을_멤버까지_가져온다() {
        // given
        Map<String, Set<String>> 받은것 = new HashMap<>();
        long totalResults = -1;

        // when
        for (long startIndex = 1; totalResults < 0 || startIndex <= totalResults; startIndex += 100) {
            JsonNode page = 조회한다("/scim/v2/Groups?startIndex={s}&count=100", startIndex);
            totalResults = page.get("totalResults").asLong();
            page.get("Resources").forEach(group -> {
                Set<String> 멤버 = new HashSet<>();
                if (group.has("members")) {
                    group.get("members").forEach(member ->
                            멤버.add(member.get("type").asText() + ":" + member.get("value").asText()));
                }
                받은것.put(group.get("id").asText(), 멤버);
            });
        }

        // then
        Map<String, Set<String>> 기대 = 조직도.snapshot().groups().values().stream()
                .collect(Collectors.toMap(DirectoryGroup::id, group -> group.members().stream()
                        .map(member -> (member.type() == MemberType.GROUP ? "Group" : "User") + ":" + member.id())
                        .collect(Collectors.toSet())));
        assertThat(받은것).isEqualTo(기대);
    }

    @Test
    @Order(4)
    @DisplayName("무작위 100명을 userName(대문자로)·externalId 로 찾으면 조회마다 몇 건만 읽는다")
    void 필터_조회는_몇_건만_읽는다() {
        // given
        Random random = new Random(42);

        for (int n = 0; n < 100; n++) {
            String id = 추가아이디(random.nextInt(추가));
            counter.reset();

            // when
            JsonNode byName = 조회한다("/scim/v2/Users?filter={f}",
                    "userName eq \"" + id.toUpperCase(Locale.ROOT) + "\"");
            JsonNode byExternal = 조회한다("/scim/v2/Users?filter={f}", "externalId eq \"ext-" + id + "\"");

            // then — userName: GSI1 1건, externalId: GSI3 1건 + GetItem 1번
            assertThat(byName.get("totalResults").asLong()).isEqualTo(1);
            assertThat(byName.get("Resources").get(0).get("id").asText()).isEqualTo(id);
            assertThat(byExternal.get("Resources").get(0).get("id").asText()).isEqualTo(id);
            assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(2);
            assertThat(counter.getItems.get()).isLessThanOrEqualTo(1);
        }
    }
}
```

- [ ] **Step 3: 컴파일을 확인한다 (구현자)**

Run: `./gradlew :app-scim:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋하고 푸시한다 (구현자)**

```bash
git add app-scim/src/test
git commit -m "test: 직원 10만 명 SCIM 목록 규모 테스트 — 읽은 아이템 수로 선형 비용을 단정" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

- [ ] **Step 5: 실행하고 결과를 기록한다 (컨트롤러)**

Run: `./gradlew :app-scim:cleanScaleTest :app-scim:scaleTest --tests '*ScimListingScaleTest'`
Expected: PASS. 출력의 "심기", "가져오기" 줄(시간, Query 수, 훑은 아이템, GetItem 수)을 스펙 §11 에 적고 커밋·푸시한다.

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 **둘 다** 돌리고 소요 시간을 스펙 §11 에 적는다. Gradle 은 하나씩.
- 사용자에게 결과를 보고하고 머지 여부를 묻는다.

# 변경 시각 내보내기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SCIM 직원·조직 응답의 `meta` 에 `created`·`lastModified` 를 싣는다.

**Architecture:**
- **storage-dynamodb 쓰기.**
  - 직원·조직 META 를 PutItem 대신 UpdateItem 으로 쓴다.
  - `updatedAt = 지금`, `createdAt = if_not_exists(createdAt, 지금)` 을 넣고, 빠진 선택 속성은 REMOVE 한다.
  - 조직 삭제는 상위 조직의 `updatedAt` 을 올린다.
- **core.** `ResourceTimes`·`Timestamped<T>` 를 두고, SCIM 전용 조회 포트(`DirectoryQueryRepository`)가 도메인 값과 시각을 함께 돌려준다. 단건 읽기 `findUser`·`findGroupHeader` 를 더한다.
- **connector-scim.** 응답을 만드는 읽기만 이 포트로 바꾸고 `ScimMeta` 에 두 칸을 더한다.
- 직원(과제 2)과 조직(과제 3)을 나눠, 과제마다 전체가 컴파일되고 테스트된다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor, AWS SDK v2 DynamoDB(UpdateItem·`if_not_exists`·ConditionExpression), Jackson, JUnit 5, AssertJ, WebTestClient, Testcontainers(DynamoDB Local, OpenFGA).

**Spec:** `docs/superpowers/specs/2026-10-09-updated-at-export-design.md`

## Global Constraints

- **테스트 표지.** 모든 테스트에 `// given`·`// when`·`// then` 표지를 둔다. 합친 `// given, when`·`// when, then`·`// given, when, then` 도 된다. 계획의 코드에 표지가 빠져 있으면 더한다.
- **이름과 글.**
  - 이름·주석·메시지는 한국어 평서문으로 쓴다. `@DisplayName` 은 한국어 문장이다.
  - 클래스 이름은 영어다.
  - 메서드 이름은 그 파일의 관례를 따른다. 공개 API·포트·DTO 는 영어, 저장소 안 도우미와 테스트는 한국어다.
- **커밋.** 제목은 한국어로 쓰고, 빈 줄 다음에 자기 하네스가 주는 `Co-Authored-By:` 줄을 단다(heredoc). 커밋마다 `git push` 한다.
- **Gradle.**
  - 한 번에 하나씩 포그라운드로 돌린다.
  - 과제가 정한 모듈 테스트만 돌린다. `scaleTest`·전체 `test` 는 컨트롤러 몫이다.
- **금지.** 서브에이전트, 파일시스템 전체 검색, 백그라운드 프로세스, `git stash` 는 쓰지 않는다. 파일은 경로로 스테이징한다.
- **테스트 수.** 결과 XML(`<모듈>/build/test-results/test/*.xml`)에서 옮긴다. 어림하지 않는다.
- **속성 이름(spec §3.1).**
  - 생성 시각 `createdAt`, 변경 시각 `updatedAt`. 값은 `Instant.toString()`.
  - 직원 선택 속성: `externalId`, `userName`, `displayName`, `displayNameKey`(`Keys.GSI2SK`), `email`, `nameFormatted`, `familyName`, `givenName`, `middleName`, `honorificPrefix`, `honorificSuffix`.
  - 조직 선택 속성: `externalId`, `displayName`.
  - 늘 있는 속성: 직원 `GSI1PK`·`GSI1SK`·`active`, 조직 `GSI1PK`·`GSI1SK`.
- **쓰기 규칙(spec §3.1).**
  - 바뀌었을 때만 쓴다(지금 그대로).
  - "같은가" 비교는 `updatedAt`·`createdAt` 둘 다 빼고 본다.
  - 처음 만들면 두 시각이 같은 값이다.
- **응답(spec §4.4).**
  - `meta` 필드 순서: `resourceType`, `created`, `lastModified`, `location`.
  - 시각은 `Instant.toString()` 문자열이고, null 이면 싣지 않는다.
  - `attributes`·`excludedAttributes` 가 `meta.created`·`meta.lastModified` 를 받는다.
- **그대로인 것(spec §4.1·§4.3).**
  - 도메인 레코드 `DirectoryUser`·`GroupHeader`·`DirectoryGroup` 은 바꾸지 않는다.
  - 락 안 쓰기 판단 읽기(`DirectoryStateRepository`)도 바꾸지 않는다.
  - 관리 API 도 바꾸지 않는다.

## Review Focus

- **같은 조직도 재동기화 쓰기 0.** `createdAt` 을 비교에서 빼지 않으면 모든 저장본이 다르게 보여 매번 전원을 다시 쓴다. 과제 1 의 기존 "쓰기 0" 테스트들과 새 테스트 `전체_동기화도_생성_시각을_지킨다` 가 붙잡는다 — `writeMeta` 만 넣고 `sameContent` 를 고치지 않으면 이 테스트들이 실패한다. 규모로는 `ReplaceWithScaleTest`(10만 명, 같은 조직도 쓰기 0)가 컨트롤러의 `scaleTest` 에서 본다.
- **값을 비운 속성이 남음.** UpdateItem 은 빠진 속성을 지우지 않는다. 선택 속성 목록에서 빠진 속성은 옛 값이 조용히 남는다. 과제 1 의 "전부 채운 뒤 전부 비우면 늘 있는 속성과 두 시각만 남는다" 테스트가 지킨다.
- **없는 상위 조직 META 생성.** UpdateItem 은 없는 아이템을 만든다. 조건(`attribute_exists`)이 없으면 지운 상위 조직의 유령 META 가 생긴다. 과제 1 테스트가 지킨다.
- **쓰기 직후 응답의 일관성.** 쓰기 뒤 응답은 방금 쓴 값을 읽어야 한다. 새 단건 읽기가 강한 일관성이 아니면 옛 `lastModified` 를 돌려줄 수 있다. 과제 2·3 의 저장소 코드가 `consistentRead(true)` 이고, 과제 4 끝단 테스트가 PATCH 응답과 GET 의 값을 맞춰 본다.
- **멤버를 흘려 쓰는 조직 응답.** 멤버를 싣는 GET·목록은 흘려 쓰는 길(`ScimGroupStream`)이 따로 있어, 시각이 빠지기 쉽다. 과제 3 과 과제 4 테스트가 멤버 포함 응답의 `meta` 를 본다.

---

### Task 1: 저장소 쓰기 — UpdateItem·생성 시각·조직 삭제의 상위 조직 변경 시각

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` (`deleteGroup` 자바독)
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/WriteCounter.java`
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`
- Modify (`.puts()` → `.writes()` 만): `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ReplaceWithScaleTest.java`, `BulkPathScaleTest.java`, `DynamoDbTupleSnapshotRepositoryTest.java`

**Interfaces:**
- Consumes: 없음.
- Produces:
  - META 아이템에 `createdAt`(문자열 `Instant.toString()`)이 생긴다. 과제 2·3 이 읽는다.
  - 테스트 도구 `WriteCounter.writes()`(PutItem + UpdateItem 수)가 `puts()` 를 대신한다.

- [ ] **Step 1: 쓰기 계측과 실패하는 테스트를 쓴다**

`WriteCounter.java` 를 아래로 바꾼다.

```java
package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 클라이언트를 감싸 아이템 쓰기(PutItem·UpdateItem) 수를 센다. "바뀌지 않았으면 쓰지 않는다" 를 호출 수로 단정하기 위한 계측이다 —
 * DynamoDB Local 은 GSI 쓰기 용량을 보여 주지 않으므로, 쓰기 요청이 없다는 것까지가 테스트로 증명할 수 있는 선이다
 * (GSI 설계 §9). 직원·조직 META 는 UpdateItem 으로 쓴다(설계 2026-10-09 §3.1).
 */
final class WriteCounter {

    private final AtomicLong writes = new AtomicLong();

    DynamoDbAsyncClient wrap(DynamoDbAsyncClient real) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putItem") || method.getName().equals("updateItem")) {
                        writes.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    long writes() {
        return writes.get();
    }

    void reset() {
        writes.set(0);
    }
}
```

다음 네 파일에서 `WriteCounter` 의 `.puts()` 호출을 모두 `.writes()` 로 바꾼다(다른 것은 고치지 않는다):
- `DynamoDbDirectoryStateRepositoryTest.java`
- `ReplaceWithScaleTest.java`
- `BulkPathScaleTest.java`
- `DynamoDbTupleSnapshotRepositoryTest.java`

`ReplaceWithScaleTest` 의 출력 문구 `PutItem` 은 `쓰기` 로 바꾼다. 단정 값(102,100 / 0 / 100)은 그대로 둔다. META 쓰기가 UpdateItem 으로 바뀌어도 `writes()` 는 같은 수를 센다.

`DynamoDbDirectoryStateRepositoryTest.java` 에 도우미를 더한다. 기존 `updatedAt(String pk)` 바로 아래에 둔다.

```java
    /** META 의 createdAt 을 직접 읽는다. 저장소 API 는 이 값을 노출하지 않는다(조회 포트가 내보낸다 — 과제 2·3). */
    private String createdAt(String pk) {
        return meta(pk).get("createdAt").s();
    }
```

같은 파일 끝(마지막 `}` 앞)에 테스트를 더한다. 필요한 import 는 이미 있다(`Duration`, `PersonName`, `MemberRef`, `DirectoryGroup`, `GroupHeader`, `Set`, `Keys`, `Attrs`).

```java
    // ---------- 생성 시각·변경 시각 (설계 2026-10-09 §3) ----------

    @Test
    @DisplayName("직원·조직을 처음 만들면 생성 시각과 변경 시각이 같다")
    void 처음_만들면_두_시각이_같다() {
        // when
        repository.saveUser(직원("kim")).block();
        repository.saveGroup(조직("DEV", "개발")).block();

        // then
        assertThat(createdAt(Keys.userPk("kim"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(updatedAt(Keys.userPk("kim"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(createdAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("직원이 바뀌면 변경 시각만 움직이고 생성 시각은 그대로다 — 이전 값을 넘기는 쓰기도 같다")
    void 직원이_바뀌어도_생성_시각은_그대로다() {
        // given
        repository.saveUser(직원("kim")).block();
        clock.앞으로(Duration.ofHours(1));

        // when — 저장본을 다시 읽지 않는 쓰기(이전 값을 넘긴다)
        repository.saveUser(직원("kim"), 직원("kim").withDisplayName("새 이름")).block();

        // then
        assertThat(createdAt(Keys.userPk("kim"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(updatedAt(Keys.userPk("kim"))).isEqualTo("2026-01-01T01:00:00Z");
    }

    @Test
    @DisplayName("조직은 멤버만 바뀌어도 변경 시각이 움직이고 생성 시각은 그대로다")
    void 조직_멤버만_바뀌어도_생성_시각은_그대로다() {
        // given
        repository.saveGroup(조직("DEV", "개발", MemberRef.user("kim"))).block();
        clock.앞으로(Duration.ofHours(1));

        // when
        repository.saveGroupChange(new GroupHeader("DEV", "cn=DEV", "개발"), Set.of(MemberRef.user("lee")), Set.of()).block();

        // then
        assertThat(createdAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T01:00:00Z");
    }

    @Test
    @DisplayName("전체 동기화도 생성 시각을 지키고, 같은 조직도를 다시 동기화하면 쓰기가 없다")
    void 전체_동기화도_생성_시각을_지킨다() {
        // given
        repository.replaceWith(new DirectorySnapshot(Map.of("kim", 직원("kim")), Map.of("DEV", 조직("DEV", "개발", MemberRef.user("kim"))))).block();
        clock.앞으로(Duration.ofHours(1));
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);

        // when — 같은 조직도
        세는.replaceWith(new DirectorySnapshot(Map.of("kim", 직원("kim")), Map.of("DEV", 조직("DEV", "개발", MemberRef.user("kim"))))).block();

        // then
        assertThat(counter.writes()).isZero();

        // when — 직원 표시명만 바뀐 조직도
        clock.앞으로(Duration.ofHours(1));
        세는.replaceWith(new DirectorySnapshot(Map.of("kim", 직원("kim").withDisplayName("새 이름")),
                Map.of("DEV", 조직("DEV", "개발", MemberRef.user("kim"))))).block();

        // then
        assertThat(createdAt(Keys.userPk("kim"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(updatedAt(Keys.userPk("kim"))).isEqualTo("2026-01-01T02:00:00Z");
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("선택 속성을 모두 비우면 META 에는 늘 있는 속성과 두 시각만 남는다 — externalId 로도 더는 찾히지 않는다")
    void 비운_선택_속성은_지워진다() {
        // given — 모든 선택 속성을 채운 직원과 조직
        DirectoryUser 전부 = new DirectoryUser("kim", "ext-kim", "kim", "김철수", "kim@example.com", true, 홍길동);
        repository.saveUser(전부).block();
        repository.saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of())).block();

        // when — 모두 비운다
        repository.saveUser(전부, new DirectoryUser("kim", null, null, null, null, true, PersonName.EMPTY)).block();
        repository.saveGroup(new DirectoryGroup("DEV", null, null, Set.of())).block();

        // then
        assertThat(meta(Keys.userPk("kim")).keySet()).containsExactlyInAnyOrder(
                Keys.PK, Keys.SK, Keys.GSI1PK, Keys.GSI1SK, "active", "updatedAt", "createdAt");
        assertThat(meta(Keys.groupPk("DEV")).keySet()).containsExactlyInAnyOrder(
                Keys.PK, Keys.SK, Keys.GSI1PK, Keys.GSI1SK, "updatedAt", "createdAt");
        assertThat(repository.findUserIdsByExternalId("ext-kim").collectList().block()).isEmpty();
        assertThat(repository.findGroupIdsByExternalId("ext-DEV").collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("하위 조직을 지우면 상위 조직의 변경 시각이 움직이고 생성 시각은 그대로다")
    void 하위_조직을_지우면_상위_조직이_바뀐다() {
        // given — 본부 ⊃ 팀 ⊃ kim
        repository.saveGroup(조직("TEAM", "팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("HQ", "본부", MemberRef.group("TEAM"))).block();
        clock.앞으로(Duration.ofHours(1));

        // when
        repository.deleteGroup("TEAM", Set.of(MemberRef.user("kim"))).block();

        // then
        assertThat(repository.findGroup("HQ").block().members()).isEmpty();
        assertThat(createdAt(Keys.groupPk("HQ"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(updatedAt(Keys.groupPk("HQ"))).isEqualTo("2026-01-01T01:00:00Z");
    }

    @Test
    @DisplayName("상위 조직의 META 가 이미 없으면 하위 조직을 지워도 META 를 만들지 않는다")
    void 없는_상위_조직_META_는_만들지_않는다() {
        // given — 본부의 META 만 사라진 모양(중간에 멈춘 삭제)
        repository.saveGroup(조직("TEAM", "팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("HQ", "본부", MemberRef.group("TEAM"))).block();
        지운다(Keys.groupPk("HQ"), Keys.META);

        // when
        repository.deleteGroup("TEAM", Set.of(MemberRef.user("kim"))).block();

        // then
        assertThat(meta(Keys.groupPk("HQ"))).isEmpty();
    }
```

이 파일에 `DirectorySnapshot` import 가 없으면 더한다(`dev.starryeye.organization.core.model.DirectorySnapshot` — 이미 있다).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: FAIL. 새 테스트들이 `createdAt` 이 없어(`NullPointerException` at `createdAt(...)`) 또는 상위 조직 `updatedAt` 이 그대로라 실패한다. 기존 테스트는 통과한다(아직 구현 전이다).

- [ ] **Step 3: 구현한다**

`DynamoDbDirectoryStateRepository.java`

(1) import 를 더한다.

```java
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
```

(2) `UPDATED_AT` 상수 다음에 둔다.

```java
    /** 처음 만든 시각. 처음 쓸 때만 넣고 이후로는 DynamoDB 가 지킨다(if_not_exists, 설계 2026-10-09 §3.1). */
    private static final String CREATED_AT = "createdAt";
```

(3) `CUT_CHILD` 상수 다음에 둔다.

```java
    /**
     * 직원 META 에서 없을 수 있는 속성 — 이번 값에 없으면 REMOVE 한다(설계 2026-10-09 §3.1). {@link #userItem} 이
     * {@code putIfPresent} 로 넣는 것과 같아야 한다. 빠지면 값을 비워도 옛 값이 남는다 — 테스트("비운 선택 속성은 지워진다")가 지킨다.
     */
    private static final Set<String> USER_OPTIONAL = Set.of(EXTERNAL_ID, USER_NAME, DISPLAY_NAME, Keys.GSI2SK, EMAIL,
            NAME_FORMATTED, FAMILY_NAME, GIVEN_NAME, MIDDLE_NAME, HONORIFIC_PREFIX, HONORIFIC_SUFFIX);

    /** 조직 META 에서 없을 수 있는 속성. {@link #groupMeta} 와 같아야 한다. */
    private static final Set<String> GROUP_OPTIONAL = Set.of(EXTERNAL_ID, DISPLAY_NAME);
```

(4) `saveUser(DirectoryUser before, DirectoryUser after)` 의 마지막 줄 `return putItem(stamped(userItem(after)));` 를 아래로 바꾼다.

```java
        return writeMeta(userItem(after), USER_OPTIONAL);
```

(5) `writeUser` 의 마지막 줄 `return putItem(stamped(userItem(user)));` 를 아래로 바꾼다.

```java
        return writeMeta(userItem(user), USER_OPTIONAL);
```

(6) `userItem` 의 자바독 `/** 직원 META 에 쓸 아이템. {@code updatedAt} 은 넣지 않는다 — 바뀌었을 때만 {@link #stamped} 가 넣는다. */` 를 아래로 바꾼다.

```java
    /** 직원 META 에 쓸 아이템. 두 시각은 넣지 않는다 — 바뀌었을 때만 {@link #writeMeta} 가 넣는다. */
```

(7) `writeMembership` 의 `Mono<Void> meta = 바뀜 ? putItem(stamped(groupMeta(header))) : Mono.empty();` 를 아래로 바꾼다.

```java
        Mono<Void> meta = 바뀜 ? writeMeta(groupMeta(header), GROUP_OPTIONAL) : Mono.empty();
```

(8) `groupMeta` 의 자바독 `/** 조직 META 에 쓸 아이템. {@code updatedAt} 은 넣지 않는다. */` 를 아래로 바꾼다.

```java
    /** 조직 META 에 쓸 아이템. 두 시각은 넣지 않는다 — {@link #writeMeta} 가 넣는다. */
```

(9) `deleteGroup` 안의 마지막 두 줄

```java
                    // 단계 순서: 멤버 줄 → 소속 줄 → META 맨 마지막
                    return Flux.concat(묶어_보낸다(멤버_줄), 묶어_보낸다(소속_줄), deleteItem(pk, Keys.META)).then();
```

을 아래로 바꾼다.

```java
                    // 단계 순서: 멤버 줄 → 상위 조직의 변경 시각 → 소속 줄 → META 맨 마지막. 상위 조직은 멤버(이 조직)가 빠졌으니
                    // 바뀐 것이다(SCIM Group 은 members 를 담는다, 설계 2026-10-09 §3.2)
                    Flux<Void> 상위_조직 = Flux.fromIterable(소속_정렬키)
                            .flatMap(sk -> 변경_시각을_올린다(Keys.parseBelongsToSk(sk)), QUERY_CONCURRENCY);
                    return Flux.concat(묶어_보낸다(멤버_줄), 상위_조직, 묶어_보낸다(소속_줄), deleteItem(pk, Keys.META)).then();
```

(10) `지우기(String pk, String sk)` 메서드 바로 앞에 둔다.

```java
    /**
     * 조직의 변경 시각만 올린다 — 멤버(지운 하위 조직)가 빠졌다(설계 2026-10-09 §3.2). META 가 없으면(이미 지워진 조직) 만들지 않는다 —
     * UpdateItem 은 없는 아이템을 만들기 때문에 조건을 단다. 조건이 깨지면 그 조직은 이미 없으니 넘어간다.
     */
    private Mono<Void> 변경_시각을_올린다(String groupId) {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.groupPk(groupId)), Keys.SK, Attrs.s(Keys.META)))
                        .updateExpression("SET #updatedAt = :now")
                        .conditionExpression("attribute_exists(#pk)")
                        .expressionAttributeNames(Map.of("#updatedAt", UPDATED_AT, "#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":now", Attrs.s(Instant.now(clock).toString())))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, 없는_조직 -> Mono.empty());
    }
```

(11) `sameContent` 를 아래로 바꾼다.

```java
    /**
     * 두 시각({@code updatedAt}·{@code createdAt})을 뺀 저장 아이템이 쓰려는 아이템과 같은가. 둘 다 빼야 한다 — 하나라도 남기면 모든 저장본이
     * 다르게 보여 매 동기화가 전원을 다시 쓴다(GSI 쏠림, 설계 2026-10-09 §3.1).
     */
    private static boolean sameContent(Map<String, AttributeValue> expected, Map<String, AttributeValue> stored) {
        Map<String, AttributeValue> withoutStamps = new HashMap<>(stored);
        withoutStamps.remove(UPDATED_AT);
        withoutStamps.remove(CREATED_AT);
        return expected.equals(withoutStamps);
    }
```

(12) `stamped` 메서드를 지우고 그 자리에 둔다.

```java
    /**
     * META 를 쓴다 — PutItem 처럼 통째로 바꾸되 생성 시각은 지킨다(설계 2026-10-09 §3.1).
     *
     * <p>키를 뺀 속성은 모두 SET 하고, {@code optional} 가운데 이번 아이템에 없는 것은 REMOVE 한다 — PutItem 의 "통째 교체" 와 같은 결과다.
     * {@code updatedAt} 은 지금, {@code createdAt} 은 처음 한 번만({@code if_not_exists}) 넣는다. 쓰기 경로는 저장본을 다시 읽지 않으므로
     * (설계 2026-10-03 §3.5) 옛 생성 시각을 손에 쥐고 있지 않다 — 보존을 DynamoDB 에 맡긴다. 처음 만들 때 두 시각은 같은 값이다
     * (RFC 7643 §3.1 MUST). 쓰기 용량·GSI 쓰기는 PutItem 과 같다.
     */
    private Mono<Void> writeMeta(Map<String, AttributeValue> item, Set<String> optional) {
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        List<String> sets = new ArrayList<>();
        for (Map.Entry<String, AttributeValue> attribute : item.entrySet()) {
            if (attribute.getKey().equals(Keys.PK) || attribute.getKey().equals(Keys.SK)) {
                continue;
            }
            String name = "#a" + sets.size();
            String value = ":a" + sets.size();
            names.put(name, attribute.getKey());
            values.put(value, attribute.getValue());
            sets.add(name + " = " + value);
        }
        names.put("#updatedAt", UPDATED_AT);
        names.put("#createdAt", CREATED_AT);
        values.put(":now", Attrs.s(Instant.now(clock).toString()));
        sets.add("#updatedAt = :now");
        sets.add("#createdAt = if_not_exists(#createdAt, :now)");

        List<String> removes = new ArrayList<>();
        for (String attribute : optional) {
            if (!item.containsKey(attribute)) {
                String name = "#r" + removes.size();
                names.put(name, attribute);
                removes.add(name);
            }
        }
        String expression = "SET " + String.join(", ", sets)
                + (removes.isEmpty() ? "" : " REMOVE " + String.join(", ", removes));
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, item.get(Keys.PK), Keys.SK, item.get(Keys.SK)))
                        .updateExpression(expression)
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .build()))
                .then();
    }
```

`putItem` 도우미는 소속 줄·멤버 줄·보류 목록이 계속 쓰므로 그대로 둔다.

`core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` — `deleteGroup` 자바독의 첫 문장

```
     * 조직을 지운다 — 상위 조직들의 "이 조직" 멤버 줄과 이 조직의 멤버 줄을 먼저, 이 조직의 소속 줄과 멤버들의 소속 줄을 그다음, META 를 맨 마지막에
     * (설계 2026-10-02 §4.1). 멤버는 호출자가 이미 읽은 것을 받는다 — 조직 파티션을 다시 훑지 않는다.
```

을 아래로 바꾼다(나머지 문단은 그대로).

```
     * 조직을 지운다 — 상위 조직들의 "이 조직" 멤버 줄과 이 조직의 멤버 줄을 먼저, 상위 조직들의 변경 시각을 그다음(멤버가 빠졌으니 바뀐 것이다,
     * 설계 2026-10-09 §3.2), 이 조직의 소속 줄과 멤버들의 소속 줄을 그다음, META 를 맨 마지막에(설계 2026-10-02 §4.1). 멤버는 호출자가
     * 이미 읽은 것을 받는다 — 조직 파티션을 다시 훑지 않는다.
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: PASS(전부). 새 테스트 7개 포함.

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS(모듈 전체). 수는 결과 XML 에서 옮긴다.

- [ ] **Step 5: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/WriteCounter.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ReplaceWithScaleTest.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/BulkPathScaleTest.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat: 직원·조직 META 를 UpdateItem 으로 쓰고 생성 시각은 if_not_exists 로 지킨다 — 빠진 선택 속성은 REMOVE, 비교는 두 시각을 빼고, 하위 조직 삭제는 상위 조직 변경 시각을 올린다

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 2: 직원 읽기 경로 — 시각 묶음, 조회 포트, SCIM 직원 응답

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/ResourceTimes.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/model/Timestamped.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryQueryRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeQueryRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`timesOf` 하나)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimMeta.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserListing.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimResourceType.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepositoryTest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimMapperTest.java`, `ScimAttributeProjectionTest.java`, `ScimUserHandlerTest.java`, `ScimUserListingTest.java`, `ScimGroupHandlerTest.java`, `ScimListHandlerTest.java`, `ScimRouteMissTest.java`, `ScimErrorTranslationTest.java`

**Interfaces:**
- Consumes (과제 1): META 의 `createdAt`·`updatedAt` 문자열 속성.
- Produces:
  - `public record ResourceTimes(Instant created, Instant lastModified)` 와 상수 `ResourceTimes.UNKNOWN`(둘 다 null).
  - `public record Timestamped<T>(T value, ResourceTimes times)`. `times` 가 null 이면 `UNKNOWN` 이다.
  - `DirectoryQueryRepository`:
    - `Flux<Timestamped<DirectoryUser>> findUsersByUserName(String)`
    - `Flux<Timestamped<DirectoryUser>> findUsersByExternalId(String)`
    - `Mono<Page<Timestamped<DirectoryUser>>> listUsers(String, int, boolean)`
    - `Mono<Timestamped<DirectoryUser>> findUser(String userId)`
    - 조직 메서드는 과제 3 에서 바뀐다.
  - `static ResourceTimes DynamoDbDirectoryStateRepository.timesOf(Map<String, AttributeValue> item)` — 패키지 공개.
  - `ScimMeta(String resourceType, String created, String lastModified, String location)`.
  - `ScimMapper.toScimUser(DirectoryUser user, ResourceTimes times)`. 조직 매퍼는 과제 3 까지 `ResourceTimes.UNKNOWN` 으로 meta 를 만든다.
  - `ScimUserHandler(DirectoryQueryRepository query, IncrementalSyncUseCase sync)` 와 `(query, sync, IgnoredAttributeObserver)`.
  - `ScimUserListing(DirectoryQueryRepository query, PageBookmarkRepository bookmarks)`.
  - `FakeQueryRepository.times` — `public final Map<String, ResourceTimes>`(아이디 → 시각). 없으면 `UNKNOWN`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryQueryRepositoryTest.java` — import 를 더한다.

```java
import dev.starryeye.organization.core.model.ResourceTimes;
import dev.starryeye.organization.core.model.Timestamped;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
```

기존 직원 테스트의 결과 다루기를 바꾼다(단정의 뜻은 그대로):
- `query.findUsersByUserName(x).collectList().block()` 처럼 직원 `Flux` 를 모으는 곳은 `.map(Timestamped::value)` 를 `collectList()` 앞에 넣는다. `List<DirectoryUser>` 변수는 그대로 둔다.
- `findUsersByExternalId` 도 같다.
- `끝까지_읽는다` 는 `Page<Timestamped<DirectoryUser>> page = …` 와 `seen.add(user.value().id())` 로 바꾼다.
- `listUsers` 결과를 쓰는 다른 테스트도 같은 방식으로 `.value()` 를 거친다.

파일 끝에 테스트를 더한다.

```java
    /** 이 시각으로 쓰는 상태 저장소. 같은 테이블을 쓴다. */
    private DynamoDbDirectoryStateRepository 시각을_정한_저장소(String at) {
        return new DynamoDbDirectoryStateRepository(client, properties, Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("직원 단건·목록·userName·externalId 찾기가 생성 시각과 변경 시각을 함께 준다")
    void 직원_읽기가_두_시각을_준다() {
        // given — 처음 만들고 한 시간 뒤 표시명을 바꾼다
        DirectoryUser 처음 = new DirectoryUser("kim", "ext-kim", "kim", "김철수", null, true);
        시각을_정한_저장소("2026-01-01T00:00:00Z").saveUser(처음).block();
        시각을_정한_저장소("2026-01-01T01:00:00Z").saveUser(처음, 처음.withDisplayName("새 이름")).block();
        ResourceTimes 기대 = new ResourceTimes(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));

        // when
        Timestamped<DirectoryUser> 단건 = query.findUser("kim").block();
        Timestamped<DirectoryUser> 이름 = query.findUsersByUserName("KIM").blockFirst();
        Timestamped<DirectoryUser> 외부 = query.findUsersByExternalId("ext-kim").blockFirst();
        Timestamped<DirectoryUser> 목록 = query.listUsers(null, 10, false).block().items().get(0);

        // then
        assertThat(단건.value().displayName()).isEqualTo("새 이름");
        assertThat(List.of(단건.times(), 이름.times(), 외부.times(), 목록.times())).containsOnly(기대);
    }

    @Test
    @DisplayName("없는 직원의 단건 읽기는 빈 결과다")
    void 없는_직원은_빈_결과다() {
        // when, then
        assertThat(query.findUser("없음").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("생성 시각이 없는 옛 아이템은 생성 시각이 null 이고 변경 시각은 있다")
    void 옛_아이템은_생성_시각이_없다() {
        // given — 이 설계 전의 모양(createdAt 없음)을 직접 쓴다
        client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(Map.of(
                        Keys.PK, AttributeValue.fromS(Keys.userPk("old")),
                        Keys.SK, AttributeValue.fromS(Keys.META),
                        Keys.GSI1PK, AttributeValue.fromS(Keys.USER_INDEX),
                        Keys.GSI1SK, AttributeValue.fromS("old"),
                        "userName", AttributeValue.fromS("old"),
                        "active", AttributeValue.fromBool(true),
                        "updatedAt", AttributeValue.fromS("2025-12-31T00:00:00Z")))
                .build()).join();

        // when
        Timestamped<DirectoryUser> found = query.findUser("old").block();

        // then
        assertThat(found.times()).isEqualTo(new ResourceTimes(null, Instant.parse("2025-12-31T00:00:00Z")));
    }
```

`ScimMapperTest.java`:
- `ScimMapper.toScimUser(x)` 호출을 모두 `ScimMapper.toScimUser(x, ResourceTimes.UNKNOWN)` 로 바꾼다.
- import `dev.starryeye.organization.core.model.ResourceTimes` 와 `java.time.Instant` 를 더한다.
- 테스트를 더한다.

```java
    @Test
    @DisplayName("직원 meta 는 resourceType·created·lastModified·location 순이고 시각은 ISO-8601 UTC 다 — 모르면 싣지 않는다")
    void 직원_meta_에_두_시각을_싣는다() {
        // given
        DirectoryUser user = new DirectoryUser("kim", null, "kim", "김철수", null, true);
        ResourceTimes times = new ResourceTimes(Instant.parse("2026-10-09T03:00:00Z"), Instant.parse("2026-10-09T04:30:00.123456Z"));

        // when
        String 있음 = ScimJson.string(ScimJson.tree(ScimMapper.toScimUser(user, times)));
        String 없음 = ScimJson.string(ScimJson.tree(ScimMapper.toScimUser(user, ResourceTimes.UNKNOWN)));

        // then
        assertThat(있음).contains("\"meta\":{\"resourceType\":\"User\",\"created\":\"2026-10-09T03:00:00Z\","
                + "\"lastModified\":\"2026-10-09T04:30:00.123456Z\",\"location\":\"/scim/v2/Users/kim\"}");
        assertThat(없음).contains("\"meta\":{\"resourceType\":\"User\",\"location\":\"/scim/v2/Users/kim\"}");
    }
```

`ScimAttributeProjectionTest.java`:
- `김철수()` 와 다른 `ScimMapper.toScimUser(x)` 호출을 시각이 있는 모양으로 바꾼다. `김철수()` 는 아래와 같다(import `ResourceTimes`, `java.time.Instant`).

```java
    private static ObjectNode 김철수() {
        return ScimJson.tree(ScimMapper.toScimUser(
                new DirectoryUser("kim", "emp-1", "kim", "김철수", "kim@example.com", true),
                new ResourceTimes(Instant.parse("2026-10-09T03:00:00Z"), Instant.parse("2026-10-09T04:00:00Z"))));
    }
```

- 파일 안의 다른 `ScimMapper.toScimUser(x)` 호출은 `ScimMapper.toScimUser(x, ResourceTimes.UNKNOWN)` 로 바꾼다.
- 테스트를 더한다.

```java
    @Test
    @DisplayName("attributes 로 meta.lastModified 만 고를 수 있다")
    void meta_lastModified_만_고른다() {
        // when
        ObjectNode node = 선택(List.of("meta.lastModified"), List.of()).apply(김철수());

        // then
        assertThat(필드(node)).containsExactlyInAnyOrder("schemas", "id", "meta");
        assertThat(필드((ObjectNode) node.get("meta"))).containsExactly("lastModified");
    }

    @Test
    @DisplayName("excludedAttributes 로 meta.created 만 뺄 수 있다")
    void meta_created_만_뺀다() {
        // when
        ObjectNode node = 선택(List.of(), List.of("meta.created")).apply(김철수());

        // then
        assertThat(필드((ObjectNode) node.get("meta"))).containsExactlyInAnyOrder("resourceType", "lastModified", "location");
    }
```

`ScimUserHandlerTest.java`:
- 두 곳(`setUp`, `클라이언트(관찰자)`)의 `new ScimUserHandler(state, useCase…)` 를 `new ScimUserHandler(query, useCase…)` 로 바꾼다.
- 같은 두 곳의 `new ScimUserListing(state, query, bookmarks)` 를 `new ScimUserListing(query, bookmarks)` 로 바꾼다. 두 곳 다 `query` 를 핸들러보다 먼저 만든다.
- 테스트를 더한다. 더할 import 는 `dev.starryeye.organization.core.model.ResourceTimes`, `java.time.Instant`, `com.fasterxml.jackson.databind.JsonNode` 다(이미 있으면 두지 않는다). `state`·`writer`·`checker`·`lock` 은 이 파일 `setUp` 이 만드는 필드다.

```java
    @Test
    @DisplayName("직원 GET 은 조회 포트가 준 생성·변경 시각을 meta 에 싣는다")
    void GET_은_meta_에_시각을_싣는다() {
        // given
        state.users.put("kim", new DirectoryUser("kim", null, "kim", "김철수", null, true));
        var query = new FakeQueryRepository(state);
        query.times.put("kim", new ResourceTimes(Instant.parse("2026-10-09T03:00:00Z"), Instant.parse("2026-10-09T04:00:00Z")));
        var useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var bookmarks = new FakePageBookmarkRepository();
        WebTestClient 시각이_있는 = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(query, useCase),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();

        // when
        JsonNode body = 시각이_있는.get().uri("/scim/v2/Users/kim").exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();

        // then
        assertThat(body.get("meta").get("created").asText()).isEqualTo("2026-10-09T03:00:00Z");
        assertThat(body.get("meta").get("lastModified").asText()).isEqualTo("2026-10-09T04:00:00Z");
    }
```

`ScimUserListingTest.java` — `new ScimUserListing(state, query, …)` 세 곳을 `new ScimUserListing(query, …)` 로 바꾼다.

`ScimGroupHandlerTest.java`, `ScimListHandlerTest.java`, `ScimRouteMissTest.java`, `ScimErrorTranslationTest.java`:
- `new ScimUserHandler(state, useCase)` 를 `new ScimUserHandler(query, useCase)` 로 바꾼다.
- `new ScimUserListing(state, query, bookmarks)` 를 `new ScimUserListing(query, bookmarks)` 로 바꾼다.
- 네 파일 모두 `query` 를 핸들러보다 먼저 만든다. `ScimGroupHandler`·`ScimGroupListing` 생성은 이 과제에서 바꾸지 않는다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:compileTestJava`
Expected: 컴파일 실패 — `ResourceTimes`·`Timestamped`·`findUser` 가 없다.

- [ ] **Step 3: 구현한다**

`core/src/main/java/dev/starryeye/organization/core/model/ResourceTimes.java`

```java
package dev.starryeye.organization.core.model;

import java.time.Instant;

/**
 * 리소스의 생성 시각과 마지막 변경 시각 — SCIM {@code meta.created}·{@code meta.lastModified}(RFC 7643 §3.1, 설계 2026-10-09 §4.1).
 * 저장본에 없으면 null 이다 — 이 설계 전에 만든 아이템에는 생성 시각이 없다(§3.3).
 */
public record ResourceTimes(Instant created, Instant lastModified) {

    /** 시각을 모를 때. 테스트 대역과, 시각 없이 그리는 응답이 쓴다. */
    public static final ResourceTimes UNKNOWN = new ResourceTimes(null, null);
}
```

`core/src/main/java/dev/starryeye/organization/core/model/Timestamped.java`

```java
package dev.starryeye.organization.core.model;

import java.util.Objects;

/**
 * 도메인 값과 그 저장본의 시각. 도메인 레코드에 시각을 넣지 않는다 — 넣으면 동등 비교·쓰기 판단·조직도 비교가 시각까지 보게 된다
 * (설계 2026-10-09 §4.1, §9). SCIM 응답을 만드는 읽기만 이 모양을 쓴다.
 */
public record Timestamped<T>(T value, ResourceTimes times) {

    public Timestamped {
        Objects.requireNonNull(value, "value");
        times = times == null ? ResourceTimes.UNKNOWN : times;
    }
}
```

`DirectoryQueryRepository.java` — import `dev.starryeye.organization.core.model.Timestamped` 를 더하고, 직원 메서드 셋을 바꾸고 단건 읽기를 더한다. 클래스 자바독 끝에 한 문단을 더한다.

```java
 * <p>직원·조직은 저장본의 생성·변경 시각과 함께 준다({@link Timestamped}, 설계 2026-10-09 §4.2) — SCIM 응답의 {@code meta} 가 쓴다.
```

```java
    Flux<Timestamped<DirectoryUser>> findUsersByUserName(String userName);

    Flux<Timestamped<DirectoryUser>> findUsersByExternalId(String externalId);
```

```java
    /** {@code userName} 소문자 순(내림차순이면 역순)으로 {@code from} 다음부터 {@code limit} 건. */
    Mono<Page<Timestamped<DirectoryUser>>> listUsers(String from, int limit, boolean descending);
```

`countGroups()` 다음에 둔다.

```java
    /** 직원 하나를 <b>강한 일관성</b>으로 읽는다 — 쓰기 직후 응답이 방금 쓴 값을 읽어야 한다(설계 2026-10-09 §4.2). 없으면 빈 Mono. */
    Mono<Timestamped<DirectoryUser>> findUser(String userId);
```

`FakeQueryRepository.java`:
- import `ResourceTimes`·`Timestamped`·`java.util.HashMap`·`java.util.Map` 를 더한다.
- 필드 `calls` 다음에 둔다.

```java
    /** 아이디(직원·조직) → 시각. 넣지 않은 아이디는 {@link ResourceTimes#UNKNOWN} 이다. */
    public final Map<String, ResourceTimes> times = new HashMap<>();
```

- 직원 메서드를 아래로 바꾸고 단건 읽기를 더한다.

```java
    @Override
    public Flux<Timestamped<DirectoryUser>> findUsersByUserName(String userName) {
        calls.add("findUsersByUserName:" + userName);
        return Flux.fromIterable(sortedUsers(false))
                .filter(user -> same(user.userName(), userName))
                .map(this::timed);
    }

    @Override
    public Flux<Timestamped<DirectoryUser>> findUsersByExternalId(String externalId) {
        calls.add("findUsersByExternalId:" + externalId);
        return Flux.fromIterable(sortedUsers(false))
                .filter(user -> externalId.equals(user.externalId()))
                .map(this::timed);
    }
```

```java
    @Override
    public Mono<Page<Timestamped<DirectoryUser>>> listUsers(String from, int limit, boolean descending) {
        calls.add("listUsers:" + from + ":" + limit);
        return Mono.just(slice(sortedUsers(descending).stream().map(this::timed).toList(), from, limit));
    }

    @Override
    public Mono<Timestamped<DirectoryUser>> findUser(String userId) {
        calls.add("findUser:" + userId);
        return Mono.justOrEmpty(state.users.get(userId)).map(this::timed);
    }

    private Timestamped<DirectoryUser> timed(DirectoryUser user) {
        return new Timestamped<>(user, times.getOrDefault(user.id(), ResourceTimes.UNKNOWN));
    }
```

`DynamoDbDirectoryStateRepository.java` — `toGroupHeader` 다음에 둔다(import `dev.starryeye.organization.core.model.ResourceTimes`).

```java
    /** META(또는 GSI1 ALL 프로젝션) 아이템의 두 시각. 없으면 null(설계 2026-10-09 §3.3). 조회 저장소가 쓴다. */
    static ResourceTimes timesOf(Map<String, AttributeValue> item) {
        return new ResourceTimes(Attrs.instant(item, CREATED_AT), Attrs.instant(item, UPDATED_AT));
    }
```

`DynamoDbDirectoryQueryRepository.java`:
- import 를 더한다.

```java
import dev.starryeye.organization.core.model.Timestamped;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
```

- 직원 메서드를 아래로 바꾸고 단건 읽기를 더한다.

```java
    @Override
    public Flux<Timestamped<DirectoryUser>> findUsersByUserName(String userName) {
        return exact(Keys.USER_INDEX, userName).map(DynamoDbDirectoryQueryRepository::user);
    }

    @Override
    public Flux<Timestamped<DirectoryUser>> findUsersByExternalId(String externalId) {
        return state.findUserIdsByExternalId(externalId).concatMap(this::findUser);
    }
```

```java
    @Override
    public Mono<Page<Timestamped<DirectoryUser>>> listUsers(String from, int limit, boolean descending) {
        return page(USERS_SCOPE, Keys.USER_INDEX, from, limit, descending, DynamoDbDirectoryQueryRepository::user);
    }
```

```java
    /** 강한 일관성 GetItem — 쓰기 직후 응답이 방금 쓴 값을 읽는다(설계 2026-10-09 §4.2). */
    @Override
    public Mono<Timestamped<DirectoryUser>> findUser(String userId) {
        return meta(Keys.userPk(userId)).map(DynamoDbDirectoryQueryRepository::user);
    }
```

- `user(...)` 도우미를 아래로 바꾸고 `meta(...)` 를 더한다.

```java
    private static Timestamped<DirectoryUser> user(Map<String, AttributeValue> item) {
        return new Timestamped<>(
                DynamoDbDirectoryStateRepository.toUser(Keys.parseUserPk(Attrs.str(item, Keys.PK)), item),
                DynamoDbDirectoryStateRepository.timesOf(item));
    }

    /** META 한 건을 <b>강한 일관성</b>으로 읽는다. 없으면 빈 Mono. */
    private Mono<Map<String, AttributeValue>> meta(String pk) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(GetItemResponse::item);
    }
```

- 필드 자바독 `/** GSI3 로 찾은 키를 본 테이블에서 강한 일관성으로 다시 읽는다. */` 는 `state` 가 이제 GSI3 아이디 찾기에만 쓰이므로 아래로 바꾼다.

```java
    /** GSI3(externalId)로 아이디를 찾는다. 아이템은 이 저장소가 본 테이블에서 강한 일관성으로 다시 읽는다. */
```

`ScimMeta.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 리소스 메타(RFC 7643 §3.1). 필드 순서는 RFC 예시 순서다. 시각은 ISO-8601 UTC 문자열({@code Instant.toString()})이고, 모르면 싣지 않는다
 * (설계 2026-10-09 §4.4). 요청 본문의 meta 는 읽기 전용이라 쓰지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimMeta(String resourceType, String created, String lastModified, String location) {
}
```

`ScimMapper.java`:
- import `dev.starryeye.organization.core.model.ResourceTimes` 와 `java.time.Instant` 를 더한다.
- `toScimUser` 를 아래로 바꾼다.

```java
    public static ScimUser toScimUser(DirectoryUser user, ResourceTimes times) {
        List<ScimEmail> emails = user.email() == null
                ? List.of()
                : List.of(new ScimEmail(user.email(), "work", true));
        return new ScimUser(
                List.of(ScimSchemas.USER),
                user.id(),
                user.externalId(),
                user.userName(),
                toScimName(user.name()),
                user.displayName(),
                emails,
                user.active(),
                meta("User", times, userLocation(user.id())));
    }
```

- 조직 두 메서드의 `new ScimMeta("Group", groupLocation(group.id()))` 와 `new ScimMeta("Group", groupLocation(header.id()))` 를 각각 `meta("Group", ResourceTimes.UNKNOWN, groupLocation(group.id()))`, `meta("Group", ResourceTimes.UNKNOWN, groupLocation(header.id()))` 로 바꾼다(과제 3 이 실제 시각으로 바꾼다).
- `groupLocation` 다음에 둔다.

```java
    /** 리소스 메타 — 시각은 모르면 싣지 않는다(설계 2026-10-09 §4.4). */
    private static ScimMeta meta(String resourceType, ResourceTimes times, String location) {
        return new ScimMeta(resourceType, text(times.created()), text(times.lastModified()), location);
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }
```

`ScimUserHandler.java`:
- import `DirectoryStateRepository` 를 `dev.starryeye.organization.core.port.DirectoryQueryRepository` 로 바꾸고 `dev.starryeye.organization.core.model.Timestamped` 를 더한다. `DirectoryUser` import 는 `본문으로` 가 쓰므로 둔다.
- 필드와 생성자를 아래로 바꾼다.

```java
    /** 응답을 만드는 읽기 — 생성·변경 시각을 함께 준다(설계 2026-10-09 §4.3). 쓰기 판단 읽기는 락 안의 유스케이스가 한다. */
    private final DirectoryQueryRepository query;
    private final IncrementalSyncUseCase sync;
    private final IgnoredAttributeObserver ignoredAttributes;

    public ScimUserHandler(DirectoryQueryRepository query, IncrementalSyncUseCase sync) {
        this(query, sync, IgnoredAttributeObserver.NOOP);
    }

    public ScimUserHandler(DirectoryQueryRepository query, IncrementalSyncUseCase sync,
                           IgnoredAttributeObserver ignoredAttributes) {
        this.query = query;
        this.sync = sync;
        this.ignoredAttributes = ignoredAttributes;
    }
```

- `get` 을 아래로 바꾼다.

```java
    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> query.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .flatMap(found -> ServerResponse.ok().contentType(SCIM_JSON)
                        .bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(found.value(), found.times()))))));
    }
```

- `patch` 의 저장 속성에 닿지 않는 갈래에서 `state.findUser(id)` 를 `query.findUser(id)` 로 바꾼다. 나머지는 그대로다. `.doOnNext(user -> …)` 와 `.flatMap(user -> 본문으로(HttpStatus.OK, id, user, projection))` 는 이제 `Timestamped<DirectoryUser>` 를 받는다.
- `respond` 의 `state.findUser(id)` 를 `query.findUser(id)` 로 바꾼다.
- `본문으로` 의 인자를 아래로 바꾼다.

```java
    private static Mono<ServerResponse> 본문으로(HttpStatus status, String id, Timestamped<DirectoryUser> found,
                                               ScimAttributeProjection projection) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(status).contentType(SCIM_JSON);
        if (status == HttpStatus.CREATED) {
            builder.location(URI.create(ScimMapper.userLocation(id)));
        }
        return builder.bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(found.value(), found.times()))));
    }
```

`ScimUserListing.java` — `state` 필드와 import 를 지우고(`DirectoryStateRepository`), import `dev.starryeye.organization.core.model.Timestamped` 를 더한다. 바뀌는 메서드는 다음과 같다.

```java
    public Mono<ScimListResponse> list(ScimQuery request) {
        Mono<ScimPager.Slice<Timestamped<DirectoryUser>>> slice = request.filter() == null
                ? ScimPager.unfiltered(ListingKind.USER, request, bookmarks, query::countUsers,
                        n -> query.skipUsers(n, request.descending()),
                        (from, limit) -> query.listUsers(from, limit, request.descending()))
                : filtered(request);
        return slice.map(page -> ScimListResponse.of(request, page.totalResults(), page.items().stream()
                .map(found -> request.projection().apply(ScimJson.tree(ScimMapper.toScimUser(found.value(), found.times()))))
                .toList()));
    }

    private Mono<ScimPager.Slice<Timestamped<DirectoryUser>>> filtered(ScimQuery request) {
        List<ScimFilter.Term> terms = request.filter().terms();
        terms.forEach(ScimUserListing::check);
        ScimFilter.Term driver = request.filter().first(INDEXED).orElseThrow(() -> ScimException.invalidFilter(
                "id·userName·externalId 중 하나의 eq 가 있어야 합니다 — 전원을 훑는 필터는 받지 않습니다"));
        return candidates(driver)
                .filter(found -> terms.stream().allMatch(term -> matches(found.value(), term)))
                .collectList()
                .map(users -> ScimPager.filtered(sort(users, request.descending()), request));
    }
```

```java
    private Flux<Timestamped<DirectoryUser>> candidates(ScimFilter.Term driver) {
        String value = (String) driver.value();
        return switch (driver.attribute()) {
            case "id" -> query.findUser(value).flux();
            case "username" -> query.findUsersByUserName(value);
            default -> query.findUsersByExternalId(value);
        };
    }
```

```java
    /** 인덱스와 같은 순서 — userName 소문자, 같으면 id. */
    private static List<Timestamped<DirectoryUser>> sort(List<Timestamped<DirectoryUser>> users, boolean descending) {
        Comparator<Timestamped<DirectoryUser>> order = Comparator
                .comparing((Timestamped<DirectoryUser> found) -> ScimText.lower(
                        found.value().userName() == null ? found.value().id() : found.value().userName()))
                .thenComparing(found -> found.value().id());
        return users.stream().sorted(descending ? order.reversed() : order).toList();
    }
```

`ScimConfig.java` — 두 빈을 바꾼다.

```java
    @Bean
    public ScimUserHandler scimUserHandler(DirectoryQueryRepository query, IncrementalSyncUseCase sync,
                                           ObjectProvider<IgnoredAttributeObserver> ignoredAttributes) {
        return new ScimUserHandler(query, sync, ignoredAttributes.getIfAvailable(() -> IgnoredAttributeObserver.NOOP));
    }
```

```java
    @Bean
    public ScimUserListing scimUserListing(DirectoryQueryRepository query, PageBookmarkRepository bookmarks) {
        return new ScimUserListing(query, bookmarks);
    }
```

`ScimResourceType.java` — USER 속성 경로의 `"active", "meta", "meta.resourcetype", "meta.location"` 를 아래로 바꾼다(GROUP 은 과제 3).

```java
            "active", "meta", "meta.resourcetype", "meta.created", "meta.lastmodified", "meta.location")),
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryQueryRepositoryTest'`
Expected: PASS.

Run: `./gradlew :connector-scim:test`
Expected: PASS(모듈 전체).

Run: `./gradlew :core:test :storage-dynamodb:test`
Expected: PASS. 수는 결과 XML 에서 옮긴다.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/model/ResourceTimes.java core/src/main/java/dev/starryeye/organization/core/model/Timestamped.java core/src/main/java/dev/starryeye/organization/core/port/DirectoryQueryRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeQueryRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepositoryTest.java connector-scim/src/main/java/dev/starryeye/organization/scim/dto/ScimMeta.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserListing.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimResourceType.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimMapperTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimAttributeProjectionTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserListingTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimListHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimRouteMissTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimErrorTranslationTest.java
git commit -m "$(cat <<'EOF'
feat: SCIM 직원 응답 meta 에 created·lastModified 를 싣는다 — 조회 포트가 도메인 값과 시각(Timestamped)을 함께 주고, 응답을 만드는 읽기를 강한 일관성 단건 읽기로

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 3: 조직 읽기 경로 — 조회 포트, 흘려 쓰는 응답, SCIM 조직 응답

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryQueryRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeQueryRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupStream.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupListing.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimResourceType.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepositoryTest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimMapperTest.java`, `ScimGroupStreamTest.java`, `ScimGroupHandlerTest.java`, `ScimUserHandlerTest.java`, `ScimListHandlerTest.java`, `ScimRouteMissTest.java`, `ScimErrorTranslationTest.java`

**Interfaces:**
- Consumes (과제 2):
  - `ResourceTimes`, `ResourceTimes.UNKNOWN`, `Timestamped<T>`.
  - `DynamoDbDirectoryStateRepository.timesOf(Map)`.
  - `DynamoDbDirectoryQueryRepository.meta(String pk)`(private 도우미).
  - `ScimMapper.meta(String, ResourceTimes, String)`(private).
  - `FakeQueryRepository.times`.
- Produces:
  - `DirectoryQueryRepository`:
    - `Flux<Timestamped<GroupHeader>> findGroupHeadersByDisplayName(String)`
    - `Flux<Timestamped<GroupHeader>> findGroupHeadersByExternalId(String)`
    - `Mono<Page<Timestamped<GroupHeader>>> listGroupHeaders(String, int, boolean)`
    - `Mono<Timestamped<GroupHeader>> findGroupHeader(String groupId)`
  - `ScimMapper.toScimGroup(DirectoryGroup, ResourceTimes)`, `ScimMapper.toScimGroup(GroupHeader, ResourceTimes)`.
  - `ScimGroupStream(DirectoryStateRepository state, DirectoryQueryRepository query)`:
    - `group(Timestamped<GroupHeader>, ScimAttributeProjection)`
    - `list(ScimQuery, long, List<Timestamped<GroupHeader>>)`
  - `ScimGroupHandler(DirectoryStateRepository state, DirectoryQueryRepository query, IncrementalSyncUseCase sync, MemberTypeResolver memberTypes)`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryQueryRepositoryTest.java`:
- 기존 조직 테스트의 결과 다루기를 바꾼다(단정의 뜻은 그대로). 조직 `Flux` 를 모으는 곳(`findGroupHeadersByDisplayName`·`findGroupHeadersByExternalId`)은 `collectList()` 앞에 `.map(Timestamped::value)` 를 넣는다.
- `listGroupHeaders` 결과는 `.value()` 를 거친다.
- 파일 끝에 테스트를 더한다(과제 2 의 `시각을_정한_저장소` 를 쓴다).

```java
    @Test
    @DisplayName("조직 단건·목록·조직명·externalId 찾기가 생성 시각과 변경 시각을 함께 준다")
    void 조직_읽기가_두_시각을_준다() {
        // given — 처음 만들고 한 시간 뒤 멤버를 넣는다(멤버만 바뀌어도 조직이 바뀐 것이다)
        시각을_정한_저장소("2026-01-01T00:00:00Z").saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of())).block();
        시각을_정한_저장소("2026-01-01T01:00:00Z")
                .saveGroupChange(new GroupHeader("DEV", "ext-DEV", "개발"), Set.of(dev.starryeye.organization.core.model.MemberRef.user("kim")), Set.of())
                .block();
        ResourceTimes 기대 = new ResourceTimes(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));

        // when
        Timestamped<GroupHeader> 단건 = query.findGroupHeader("DEV").block();
        Timestamped<GroupHeader> 이름 = query.findGroupHeadersByDisplayName("개발").blockFirst();
        Timestamped<GroupHeader> 외부 = query.findGroupHeadersByExternalId("ext-DEV").blockFirst();
        Timestamped<GroupHeader> 목록 = query.listGroupHeaders(null, 10, false).block().items().get(0);

        // then
        assertThat(단건.value()).isEqualTo(new GroupHeader("DEV", "ext-DEV", "개발"));
        assertThat(List.of(단건.times(), 이름.times(), 외부.times(), 목록.times())).containsOnly(기대);
        assertThat(query.findGroupHeader("없음").blockOptional()).isEmpty();
    }
```

`ScimMapperTest.java`:
- `ScimMapper.toScimGroup(x)` 호출을 `ScimMapper.toScimGroup(x, ResourceTimes.UNKNOWN)` 으로 바꾼다.
- 테스트를 더한다.

```java
    @Test
    @DisplayName("조직 meta 도 resourceType·created·lastModified·location 순이다")
    void 조직_meta_에_두_시각을_싣는다() {
        // given
        ResourceTimes times = new ResourceTimes(Instant.parse("2026-10-09T03:00:00Z"), Instant.parse("2026-10-09T05:00:00Z"));

        // when
        String 헤더 = ScimJson.string(ScimJson.tree(ScimMapper.toScimGroup(new GroupHeader("DEV", null, "개발"), times)));

        // then
        assertThat(헤더).contains("\"meta\":{\"resourceType\":\"Group\",\"created\":\"2026-10-09T03:00:00Z\","
                + "\"lastModified\":\"2026-10-09T05:00:00Z\",\"location\":\"/scim/v2/Groups/DEV\"}");
    }
```

`ScimGroupStreamTest.java` — import `ResourceTimes`·`Timestamped`·`FakeQueryRepository`·`java.time.Instant` 를 더하고 도우미를 둔다.

```java
    private static Timestamped<GroupHeader> 시각_없이(GroupHeader header) {
        return new Timestamped<>(header, ResourceTimes.UNKNOWN);
    }
```

- `new ScimGroupStream(state)` 를 모두 `new ScimGroupStream(state, new FakeQueryRepository(state))` 로 바꾼다. 익명 하위 클래스로 만든 `state` 도 그대로 넘긴다.
- `.group(new GroupHeader(…), …)` 는 `.group(시각_없이(new GroupHeader(…)), …)` 로 바꾼다.
- `List<GroupHeader> headers = List.of(new GroupHeader(…), …)` 는 `List<Timestamped<GroupHeader>> headers = List.of(시각_없이(new GroupHeader(…)), …)` 로 바꾼다.
- `ScimMapper.toScimGroup(x)` 는 `ScimMapper.toScimGroup(x, ResourceTimes.UNKNOWN)` 으로 바꾼다.
- 테스트를 더한다.

```java
    @Test
    @DisplayName("멤버를 흘려 쓰는 조직 응답도 meta 에 생성·변경 시각을 싣는다 — 목록은 조직마다 다시 읽은 시각이다")
    void 흘려_쓰는_응답도_시각을_싣는다() throws Exception {
        // given
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("DEV", null, "개발", Set.of(MemberRef.user("kim")))).block();
        var 조회 = new FakeQueryRepository(state);
        조회.times.put("DEV", new ResourceTimes(Instant.parse("2026-10-09T03:00:00Z"), Instant.parse("2026-10-09T04:00:00Z")));
        var stream = new ScimGroupStream(state, 조회);
        var query = new ScimQuery(null, 1, 100, false, ScimAttributeProjection.all());

        // when — 목록은 넘겨받은 머리(시각 없음)가 아니라 다시 읽은 머리의 시각을 쓴다
        JsonNode 하나 = JSON.readTree(모은다(stream.group(조회.findGroupHeader("DEV").block(), ScimAttributeProjection.all())));
        JsonNode 목록 = JSON.readTree(모은다(stream.list(query, 1, List.of(시각_없이(new GroupHeader("DEV", null, "개발"))))));

        // then
        assertThat(하나.get("meta").get("lastModified").asText()).isEqualTo("2026-10-09T04:00:00Z");
        assertThat(목록.get("Resources").get(0).get("meta").get("created").asText()).isEqualTo("2026-10-09T03:00:00Z");
    }
```

`JSON`·`모은다` 는 이 파일의 기존 도우미다. `ScimQuery` 생성은 이 파일의 기존 목록 테스트와 같다. 이 테스트를 위해 더할 import 는 `dev.starryeye.organization.core.fake.FakeQueryRepository`, `dev.starryeye.organization.core.model.ResourceTimes`, `dev.starryeye.organization.core.model.Timestamped`, `java.time.Instant` 다(나머지는 이미 있다).

`ScimGroupHandlerTest.java`, `ScimUserHandlerTest.java`(`setUp`·`클라이언트(관찰자)`·과제 2 에서 더한 테스트), `ScimListHandlerTest.java`, `ScimRouteMissTest.java`, `ScimErrorTranslationTest.java` — `new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state))` 를 모두 `new ScimGroupHandler(state, query, useCase, new StateMemberTypeResolver(state))` 로 바꾼다.

`ScimGroupHandlerTest.java` 에 테스트를 더한다(`setUp` 이 만드는 것과 같은 모양으로 `query` 에 시각을 넣은 클라이언트를 만든다. import `ResourceTimes`·`java.time.Instant`·`JsonNode` 가 없으면 더한다).

```java
    @Test
    @DisplayName("조직 GET 은 멤버를 싣든 빼든 조회 포트가 준 생성·변경 시각을 meta 에 싣는다")
    void GET_은_meta_에_시각을_싣는다() {
        // given
        state.groups.put("DEV", new DirectoryGroup("DEV", null, "개발", Set.of()));
        var query = new FakeQueryRepository(state);
        query.times.put("DEV", new ResourceTimes(Instant.parse("2026-10-09T03:00:00Z"), Instant.parse("2026-10-09T04:00:00Z")));
        var useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var bookmarks = new FakePageBookmarkRepository();
        WebTestClient 시각이_있는 = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(query, useCase),
                        new ScimGroupHandler(state, query, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();

        // when
        JsonNode 멤버포함 = 시각이_있는.get().uri("/scim/v2/Groups/DEV").exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        JsonNode 멤버제외 = 시각이_있는.get().uri("/scim/v2/Groups/DEV?excludedAttributes=members").exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        // then
        assertThat(멤버포함.get("meta").get("lastModified").asText()).isEqualTo("2026-10-09T04:00:00Z");
        assertThat(멤버제외.get("meta").get("created").asText()).isEqualTo("2026-10-09T03:00:00Z");
    }
```

`state`·`writer`·`checker`·`lock` 은 이 파일 `setUp` 이 만드는 필드다. 더할 import 는 `com.fasterxml.jackson.databind.JsonNode`, `dev.starryeye.organization.core.model.DirectoryGroup`, `dev.starryeye.organization.core.model.ResourceTimes`, `java.time.Instant`, `java.util.Set`(이미 있는 것은 두지 않는다)이다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:compileTestJava`
Expected: 컴파일 실패 — `findGroupHeader`·`ScimGroupStream(state, query)`·`toScimGroup(x, times)` 가 없다.

- [ ] **Step 3: 구현한다**

`DirectoryQueryRepository.java` — 조직 메서드 셋을 바꾸고 단건 읽기를 더한다.

```java
    Flux<Timestamped<GroupHeader>> findGroupHeadersByDisplayName(String displayName);

    Flux<Timestamped<GroupHeader>> findGroupHeadersByExternalId(String externalId);
```

```java
    /** 조직명 소문자 순(내림차순이면 역순)으로 {@code from} 다음부터 {@code limit} 건. 멤버는 담지 않는다. */
    Mono<Page<Timestamped<GroupHeader>>> listGroupHeaders(String from, int limit, boolean descending);
```

`findUser` 다음에 둔다.

```java
    /** 조직 이름표 하나를 <b>강한 일관성</b>으로 읽는다(멤버 없음). 없으면 빈 Mono. */
    Mono<Timestamped<GroupHeader>> findGroupHeader(String groupId);
```

`FakeQueryRepository.java` — 조직 메서드를 아래로 바꾸고 단건 읽기를 더한다.

```java
    @Override
    public Flux<Timestamped<GroupHeader>> findGroupHeadersByDisplayName(String displayName) {
        calls.add("findGroupHeadersByDisplayName:" + displayName);
        return Flux.fromIterable(sortedGroups(false))
                .filter(group -> same(group.displayName(), displayName))
                .map(this::timed);
    }

    @Override
    public Flux<Timestamped<GroupHeader>> findGroupHeadersByExternalId(String externalId) {
        calls.add("findGroupHeadersByExternalId:" + externalId);
        return Flux.fromIterable(sortedGroups(false))
                .filter(group -> externalId.equals(group.externalId()))
                .map(this::timed);
    }
```

```java
    @Override
    public Mono<Page<Timestamped<GroupHeader>>> listGroupHeaders(String from, int limit, boolean descending) {
        calls.add("listGroupHeaders:" + from + ":" + limit);
        return Mono.just(slice(sortedGroups(descending).stream().map(this::timed).toList(), from, limit));
    }

    @Override
    public Mono<Timestamped<GroupHeader>> findGroupHeader(String groupId) {
        calls.add("findGroupHeader:" + groupId);
        return Mono.justOrEmpty(state.groups.get(groupId)).map(FakeQueryRepository::header).map(this::timed);
    }

    private Timestamped<GroupHeader> timed(GroupHeader header) {
        return new Timestamped<>(header, times.getOrDefault(header.id(), ResourceTimes.UNKNOWN));
    }
```

`DynamoDbDirectoryQueryRepository.java` — 조직 메서드를 바꾸고 단건 읽기를 더한다.

```java
    @Override
    public Flux<Timestamped<GroupHeader>> findGroupHeadersByDisplayName(String displayName) {
        return exact(Keys.GROUP_INDEX, displayName).map(DynamoDbDirectoryQueryRepository::group);
    }

    @Override
    public Flux<Timestamped<GroupHeader>> findGroupHeadersByExternalId(String externalId) {
        return state.findGroupIdsByExternalId(externalId).concatMap(this::findGroupHeader);
    }
```

```java
    @Override
    public Mono<Page<Timestamped<GroupHeader>>> listGroupHeaders(String from, int limit, boolean descending) {
        return page(GROUPS_SCOPE, Keys.GROUP_INDEX, from, limit, descending, DynamoDbDirectoryQueryRepository::group);
    }
```

```java
    /** 강한 일관성 GetItem — 쓰기 직후 응답이 방금 쓴 값을 읽는다(설계 2026-10-09 §4.2). */
    @Override
    public Mono<Timestamped<GroupHeader>> findGroupHeader(String groupId) {
        return meta(Keys.groupPk(groupId)).map(DynamoDbDirectoryQueryRepository::group);
    }
```

```java
    private static Timestamped<GroupHeader> group(Map<String, AttributeValue> item) {
        return new Timestamped<>(
                DynamoDbDirectoryStateRepository.toGroupHeader(Keys.parseGroupPk(Attrs.str(item, Keys.PK)), item),
                DynamoDbDirectoryStateRepository.timesOf(item));
    }
```

`ScimMapper.java` — 조직 두 메서드를 아래로 바꾼다.

```java
    public static ScimGroup toScimGroup(DirectoryGroup group, ResourceTimes times) {
        List<ScimMember> members = group.members().stream()
                .map(ScimMapper::toScimMember)
                .toList();
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                group.id(),
                group.externalId(),
                group.displayName(),
                members,
                meta("Group", times, groupLocation(group.id())));
    }
```

```java
    /** 멤버 없이 조직을 그린다 — {@code members} 가 응답에 필요 없을 때 멤버 줄을 읽지 않기 위해서다. */
    public static ScimGroup toScimGroup(GroupHeader header, ResourceTimes times) {
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                header.id(),
                header.externalId(),
                header.displayName(),
                null,
                meta("Group", times, groupLocation(header.id())));
    }
```

`ScimGroupStream.java`:
- import `dev.starryeye.organization.core.model.Timestamped` 와 `dev.starryeye.organization.core.port.DirectoryQueryRepository` 를 더한다.
- 필드·생성자·`group` 두 개·`list`·`head` 를 아래로 바꾼다. `listHead` 의 인자 이름도 `request` 로 바꾼다.

```java
    private final DirectoryStateRepository state;
    /** 머리(이름표와 시각)를 읽는다 — 응답의 meta 가 생성·변경 시각을 싣는다(설계 2026-10-09 §4.3). 멤버 줄은 상태 저장소에서 읽는다. */
    private final DirectoryQueryRepository query;

    ScimGroupStream(DirectoryStateRepository state, DirectoryQueryRepository query) {
        this.state = state;
        this.query = query;
    }

    /**
     * 조직 하나. 헤더는 호출자가 이미 읽었다 — 없는 조직이면 응답을 쓰기 전에 404 를 냈다.
     * 이 부품은 늘 {@code members} 를 쓴다 — 부른 쪽이 {@code projection.includes("members")} 일 때만 부른다. 아니면 이름표 트리 응답을 쓴다.
     */
    Flux<DataBuffer> group(Timestamped<GroupHeader> header, ScimAttributeProjection projection) {
        return group("", header, projection);
    }

    /** {@code prefix} 는 응답 앞에 붙는 글자다 — 목록에서 앞 조직과 이을 쉼표. 빈 버퍼를 따로 내보내지 않으려고 앞부분에 합친다. */
    private Flux<DataBuffer> group(String prefix, Timestamped<GroupHeader> header, ScimAttributeProjection projection) {
        return Flux.concat(
                Mono.fromSupplier(() -> buffer(prefix + head(header, projection))),
                members(header.value().id(), projection),
                Mono.fromSupplier(() -> buffer("]}")));
    }
```

```java
    Flux<DataBuffer> list(ScimQuery request, long totalResults, List<Timestamped<GroupHeader>> headers) {
        return Flux.defer(() -> {
            AtomicInteger 쓴_수 = new AtomicInteger();
            Flux<DataBuffer> resources = Flux.fromIterable(headers)
                    .concatMap(listed -> query.findGroupHeader(listed.value().id())
                            .flatMapMany(header -> group(쓴_수.getAndIncrement() == 0 ? "" : ",", header, request.projection())));
            return Flux.concat(
                    Mono.fromSupplier(() -> buffer(listHead(request, totalResults))),
                    resources,
                    Mono.fromSupplier(() -> buffer("],\"itemsPerPage\":" + 쓴_수.get() + "}")));
        });
    }

    private static String head(Timestamped<GroupHeader> header, ScimAttributeProjection projection) {
        // 헤더로 만든 조직에는 members 가 없다(null 은 쓰지 않는다). id·schemas 는 늘 남아 객체가 비지 않는다
        String json = ScimJson.string(projection.apply(ScimJson.tree(ScimMapper.toScimGroup(header.value(), header.times()))));
        return json.substring(0, json.length() - 1) + ",\"members\":[";
    }
```

`list` 의 자바독(바로 위 주석)은 그대로 둔다.

`ScimGroupHandler.java`:
- import `dev.starryeye.organization.core.port.DirectoryQueryRepository` 를 더한다.
- 필드·생성자를 아래로 바꾸고, `body` 의 읽기를 바꾼다.

```java
    /** 응답을 만드는 읽기 — 생성·변경 시각을 함께 준다(설계 2026-10-09 §4.3). 쓰기 판단 읽기는 락 안의 유스케이스가 한다. */
    private final DirectoryQueryRepository query;
    private final IncrementalSyncUseCase sync;
    private final MemberTypeResolver memberTypes;
    private final ScimGroupStream stream;

    public ScimGroupHandler(DirectoryStateRepository state, DirectoryQueryRepository query, IncrementalSyncUseCase sync,
                            MemberTypeResolver memberTypes) {
        this.query = query;
        this.sync = sync;
        this.memberTypes = memberTypes;
        this.stream = new ScimGroupStream(state, query);
    }
```

```java
    private Mono<ServerResponse> body(HttpStatus status, String id, ScimAttributeProjection projection,
                                      ScimException missing) {
        return query.findGroupHeader(id)
                .switchIfEmpty(Mono.error(missing))
                .flatMap(found -> projection.includes("members")
                        ? builder(status, id).body(BodyInserters.fromDataBuffers(stream.group(found, projection)))
                        : builder(status, id).bodyValue(projection.apply(ScimJson.tree(
                                ScimMapper.toScimGroup(found.value(), found.times())))));
    }
```

`body` 의 자바독은 그대로 둔다.

`ScimGroupListing.java`:
- `state` 필드를 지운다(생성자의 `state` 인자는 흘려 쓰기 부품에 넘긴다).
- import `dev.starryeye.organization.core.model.Timestamped` 를 더한다.
- 바뀌는 곳은 다음과 같다.

```java
    private final DirectoryQueryRepository query;
    private final PageBookmarkRepository bookmarks;
    private final ScimGroupStream stream;

    public ScimGroupListing(DirectoryStateRepository state, DirectoryQueryRepository query, PageBookmarkRepository bookmarks) {
        this.query = query;
        this.bookmarks = bookmarks;
        this.stream = new ScimGroupStream(state, query);
    }

    /** 이름표만 싣는 목록을 한 번에 만든다. 멤버를 싣는 목록은 {@link #streamed} 다. */
    public Mono<ScimListResponse> list(ScimQuery request) {
        return slice(request).map(page -> ScimListResponse.of(request, page.totalResults(), page.items().stream()
                .map(found -> request.projection().apply(ScimJson.tree(ScimMapper.toScimGroup(found.value(), found.times()))))
                .toList()));
    }
```

```java
    /** 조회가 가리키는 쪽의 조직 이름표와 전체 수. 멤버는 읽지 않는다. */
    Mono<ScimPager.Slice<Timestamped<GroupHeader>>> slice(ScimQuery request) {
        return request.filter() == null
                ? ScimPager.unfiltered(ListingKind.GROUP, request, bookmarks, query::countGroups,
                        n -> query.skipGroups(n, request.descending()),
                        (from, limit) -> query.listGroupHeaders(from, limit, request.descending()))
                : filtered(request);
    }

    private Mono<ScimPager.Slice<Timestamped<GroupHeader>>> filtered(ScimQuery request) {
        List<ScimFilter.Term> terms = request.filter().terms();
        terms.forEach(ScimGroupListing::check);
        ScimFilter.Term driver = request.filter().first(INDEXED).orElseThrow(() ->
                ScimException.invalidFilter("id·displayName·externalId 중 하나의 eq 가 있어야 합니다"));
        return candidates(driver)
                .filter(found -> terms.stream().allMatch(term -> matches(found.value(), term)))
                .collectList()
                .map(groups -> ScimPager.filtered(sort(groups, request.descending()), request));
    }
```

```java
    private Flux<Timestamped<GroupHeader>> candidates(ScimFilter.Term driver) {
        String value = (String) driver.value();
        return switch (driver.attribute()) {
            case "id" -> query.findGroupHeader(value).flux();
            case "displayname" -> query.findGroupHeadersByDisplayName(value);
            default -> query.findGroupHeadersByExternalId(value);
        };
    }
```

```java
    private static List<Timestamped<GroupHeader>> sort(List<Timestamped<GroupHeader>> groups, boolean descending) {
        Comparator<Timestamped<GroupHeader>> order = Comparator
                .comparing((Timestamped<GroupHeader> found) -> ScimText.lower(
                        found.value().displayName() == null ? found.value().id() : found.value().displayName()))
                .thenComparing(found -> found.value().id());
        return groups.stream().sorted(descending ? order.reversed() : order).toList();
    }
```

`ScimConfig.java` — 조직 핸들러 빈을 바꾼다.

```java
    @Bean
    public ScimGroupHandler scimGroupHandler(DirectoryStateRepository state, DirectoryQueryRepository query,
                                             IncrementalSyncUseCase sync, MemberTypeResolver memberTypes) {
        return new ScimGroupHandler(state, query, sync, memberTypes);
    }
```

`ScimResourceType.java` — GROUP 속성 경로의 `"meta", "meta.resourcetype", "meta.location"));` 를 아래로 바꾼다.

```java
            "meta", "meta.resourcetype", "meta.created", "meta.lastmodified", "meta.location"));
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryQueryRepositoryTest'`
Expected: PASS.

Run: `./gradlew :connector-scim:test`
Expected: PASS(모듈 전체).

Run: `./gradlew :core:test :storage-dynamodb:test`
Expected: PASS. 수는 결과 XML 에서 옮긴다.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryQueryRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeQueryRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepositoryTest.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupStream.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupListing.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimResourceType.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimMapperTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupStreamTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimListHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimRouteMissTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimErrorTranslationTest.java
git commit -m "$(cat <<'EOF'
feat: SCIM 조직 응답 meta 에 created·lastModified 를 싣는다 — 조회 포트의 조직 읽기가 시각을 함께 주고, 멤버를 흘려 쓰는 응답도 다시 읽은 머리의 시각을 쓴다

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 4: 끝단 검증(app-scim)과 README

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimMetaTimesEndToEndTest.java`
- Modify: `README.md` (SCIM "요청 형식" 의 `Location` 줄 다음)

**Interfaces:**
- Consumes (과제 1~3): 실제 컨테이너 위의 SCIM 응답 `meta.created`·`meta.lastModified`(ISO-8601 UTC 문자열).
- Produces: 없음.

- [ ] **Step 1: 끝단 테스트를 쓴다**

```java
package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.core.fixture.Containers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 응답의 {@code meta.created}·{@code meta.lastModified} 를 실제 컨테이너 위에서 본다(설계 2026-10-09 §7). 시각은 서버 시계라 값이 아니라
 * 같은가·뒤인가로 본다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimMetaTimesEndToEndTest {

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;

    private JsonNode 보낸다(WebTestClient.RequestHeadersSpec<?> request, int status) {
        return request.exchange().expectStatus().isEqualTo(status)
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private JsonNode 직원을_만든다(String userName) {
        return 보낸다(client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s","displayName":"%s","active":true}
                        """.formatted(userName, userName)), 201);
    }

    private JsonNode 조직을_만든다(String name, String membersJson) {
        return 보낸다(client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"%s","members":[%s]}
                        """.formatted(name, membersJson)), 201);
    }

    /** URI 템플릿과 변수 — 필터 값의 따옴표·공백은 변수로 넘겨 인코딩을 WebTestClient 에 맡긴다(ScimQueryEndToEndTest 와 같다). */
    private JsonNode 읽는다(String uriTemplate, Object... variables) {
        return 보낸다(client.get().uri(uriTemplate, variables), 200);
    }

    private WebTestClient.ResponseSpec 패치(String path, String operations) {
        return client.patch().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}
                        """.formatted(operations))
                .exchange();
    }

    private static Instant created(JsonNode resource) {
        return Instant.parse(resource.get("meta").get("created").asText());
    }

    private static Instant lastModified(JsonNode resource) {
        return Instant.parse(resource.get("meta").get("lastModified").asText());
    }

    @Test
    @DisplayName("직원: 만들면 두 시각이 같고, 같은 값 PATCH 는 그대로, 바꾸는 PATCH·PUT 은 변경 시각만 움직인다")
    void 직원의_두_시각() {
        // given
        JsonNode 만든 = 직원을_만든다("meta-kim");
        String id = 만든.get("id").asText();
        String 경로 = "/scim/v2/Users/" + id;

        // then — 만들면 같다, GET 도 같은 값이다
        assertThat(created(만든)).isEqualTo(lastModified(만든));
        assertThat(lastModified(읽는다(경로))).isEqualTo(lastModified(만든));

        // when — 같은 값
        JsonNode 같은값 = 패치(경로, """
                [{"op":"replace","path":"displayName","value":"meta-kim"}]
                """).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        // then
        assertThat(lastModified(같은값)).isEqualTo(lastModified(만든));

        // when — 바꾸는 PATCH
        JsonNode 바꾼 = 패치(경로, """
                [{"op":"replace","path":"displayName","value":"김메타"}]
                """).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        // then — 응답과 GET 이 같은 값이고, 생성 시각은 그대로다
        assertThat(lastModified(바꾼)).isAfter(lastModified(만든));
        assertThat(created(바꾼)).isEqualTo(created(만든));
        assertThat(lastModified(읽는다(경로))).isEqualTo(lastModified(바꾼));

        // when — PUT
        JsonNode 교체 = 보낸다(client.put().uri(경로).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"meta-kim","displayName":"김메타2","active":true}
                        """), 200);

        // then
        assertThat(lastModified(교체)).isAfter(lastModified(바꾼));
        assertThat(created(교체)).isEqualTo(created(만든));
    }

    @Test
    @DisplayName("조직: 멤버만 넣고 빼도 변경 시각이 움직이고, 멤버를 흘려 쓰는 GET 과 빼는 GET 이 같은 값이다")
    void 조직의_두_시각() {
        // given
        String 직원 = 직원을_만든다("meta-member").get("id").asText();
        JsonNode 만든 = 조직을_만든다("메타팀", "");
        String 경로 = "/scim/v2/Groups/" + 만든.get("id").asText();
        assertThat(created(만든)).isEqualTo(lastModified(만든));

        // when — 멤버 넣기
        패치(경로, """
                [{"op":"add","path":"members","value":[{"value":"%s","type":"User"}]}]
                """.formatted(직원)).expectStatus().isNoContent();
        JsonNode 넣은뒤 = 읽는다(경로);

        // then
        assertThat(lastModified(넣은뒤)).isAfter(lastModified(만든));
        assertThat(created(넣은뒤)).isEqualTo(created(만든));
        assertThat(lastModified(읽는다(경로 + "?excludedAttributes=members"))).isEqualTo(lastModified(넣은뒤));

        // when — 멤버 빼기
        패치(경로, """
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]
                """.formatted(직원)).expectStatus().isNoContent();

        // then
        assertThat(lastModified(읽는다(경로))).isAfter(lastModified(넣은뒤));
    }

    @Test
    @DisplayName("하위 조직을 지우면 상위 조직의 변경 시각이 움직이고 생성 시각은 그대로다")
    void 하위_조직_삭제는_상위_조직을_바꾼다() {
        // given — 본부 ⊃ 팀
        String 팀 = 조직을_만든다("메타하위팀", "").get("id").asText();
        JsonNode 본부 = 조직을_만든다("메타본부", "{\"value\":\"%s\",\"type\":\"Group\"}".formatted(팀));
        String 본부경로 = "/scim/v2/Groups/" + 본부.get("id").asText();

        // when
        client.delete().uri("/scim/v2/Groups/" + 팀).exchange().expectStatus().isNoContent();

        // then
        JsonNode 지운뒤 = 읽는다(본부경로);
        assertThat(지운뒤.has("members") && 지운뒤.get("members").size() > 0).isFalse();
        assertThat(lastModified(지운뒤)).isAfter(lastModified(본부));
        assertThat(created(지운뒤)).isEqualTo(created(본부));
    }

    @Test
    @DisplayName("목록·필터 응답도 같은 시각을 싣고, attributes·excludedAttributes 가 meta 의 두 시각을 고르고 뺀다")
    void 목록과_속성_선택() {
        // given
        JsonNode 직원 = 직원을_만든다("meta-list");
        JsonNode 조직 = 조직을_만든다("메타목록팀", "");

        // when
        JsonNode 직원필터 = 읽는다("/scim/v2/Users?filter={f}", "userName eq \"meta-list\"");
        JsonNode 조직필터 = 읽는다("/scim/v2/Groups?filter={f}", "displayName eq \"메타목록팀\"");
        JsonNode 고름 = 읽는다("/scim/v2/Users/" + 직원.get("id").asText() + "?attributes=meta.lastModified");
        JsonNode 뺌 = 읽는다("/scim/v2/Groups/" + 조직.get("id").asText() + "?excludedAttributes=meta.created");

        // then
        assertThat(lastModified(직원필터.get("Resources").get(0))).isEqualTo(lastModified(직원));
        assertThat(created(조직필터.get("Resources").get(0))).isEqualTo(created(조직));
        assertThat(고름.get("meta").has("lastModified")).isTrue();
        assertThat(고름.get("meta").has("created")).isFalse();
        assertThat(뺌.get("meta").has("created")).isFalse();
        assertThat(뺌.get("meta").has("lastModified")).isTrue();
    }
}
```

`Containers` 는 `ScimGroupMemberPatchEndToEndTest` 와 같은 `dev.starryeye.organization.core.fixture.Containers` 다. 경로만 넘기는 `읽는다(경로)` 는 변수 없는 URI 템플릿이다 — 경로에 중괄호가 없으므로 그대로 간다.

- [ ] **Step 2: 돌려 본다**

Run: `./gradlew :app-scim:test --tests 'dev.starryeye.organization.scim.app.ScimMetaTimesEndToEndTest'`
Expected: PASS 4개(과제 1~3 이 들어간 뒤라 처음부터 통과한다 — 이 과제의 RED 는 과제 2·3 의 단위 테스트가 이미 보였다).

- [ ] **Step 3: README 를 고친다**

`README.md` — "**요청 형식.**" 목록의 `Location` 줄
```
- 직원·조직 POST 의 201 에는 `Location` 헤더가 붙는다. 값은 본문 `meta.location` 과 같은 상대 경로(`/scim/v2/Users/<id>`, `/scim/v2/Groups/<id>`)다(RFC 7644 §3.3).
```
다음에 한 줄을 더한다.

```
- 직원·조직 응답의 `meta` 에는 `created`·`lastModified` 가 있다(RFC 7643 §3.1, ISO-8601 UTC). 처음 만들면 둘이 같고, `lastModified` 는 저장하는 속성이
  실제로 바뀔 때만 움직인다 — 같은 값을 다시 보내면 그대로다. 조직은 멤버만 바뀌어도(하위 조직이 지워져도) 움직인다. 직원의 소속 변경은 직원의 변경이
  아니다(User `groups` 를 내보내지 않는다). 이 기능 전에 만든 아이템에는 `created` 가 없고 다음 변경 시각이 생성 시각으로 들어간다 — 운영 배포 전이라
  이관하지 않는다(테이블을 다시 만든다). `meta.lastModified` 로 거르는 필터는 받지 않는다(400). 설계: `docs/superpowers/specs/2026-10-09-updated-at-export-design.md`.
```

- [ ] **Step 4: 커밋한다**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimMetaTimesEndToEndTest.java README.md
git commit -m "$(cat <<'EOF'
test: SCIM meta 의 created·lastModified 끝단 검증 — 직원 POST·같은 값 PATCH·PATCH·PUT, 조직 멤버 넣고 빼기, 하위 조직 삭제, 목록·필터, 속성 선택 / README

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

# GSI1 쏠림 — 바뀐 것만 쓴다 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 직원·조직 META 를 저장된 것과 비교해 바뀐 것만 쓰게 해, LDAP 전체 동기화가 매 회차 GSI1 `USER_INDEX` 로 10만 건을 몰아 쓰던 것을 실제 변경 수로 줄이고 `updatedAt` 을 진짜 변경 시각으로 만든다.

**Architecture:** 변경은 `DynamoDbDirectoryStateRepository` 한 곳이다. 저장본을 "도메인 값 + 키가 지금 규칙대로인가" 로 읽는 `Stored<T>` 를 두고, `saveUser`·`saveGroup` 은 META 를 강한 일관성으로 한 건 읽어 비교하고, `replaceWith` 는 삭제 판단 때문에 이미 훑던 GSI1 조회에서 저장본을 함께 받아 비교한다. 다를 때만 PutItem 하고 그때만 `updatedAt` 을 넣는다.

**Tech Stack:** Java 17, AWS SDK v2 DynamoDB(async), Reactor, JUnit 5, AssertJ, Testcontainers(DynamoDB Local).

**Spec:** [`docs/superpowers/specs/2026-09-25-gsi1-hot-partition-design.md`](../specs/2026-09-25-gsi1-hot-partition-design.md)

## Global Constraints

- 테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석. 기존 테스트의 형태를 그대로 따른다. Lombok 을 쓴다.
- **규칙(스펙 §3):** 저장소는 바뀐 직원·조직 META 만 쓰고, 그때만 `updatedAt` 을 넣는다. "바뀌었다" 는 `updatedAt` 을 뺀 아이템 전체(인덱스 키 포함)가 저장된 것과 다른 것이다. 저장된 것이 없으면 새로 생긴 것이다.
- **조직의 변경은 META 의 변경 또는 멤버 구성의 변경이다.** 멤버 구성에 차이가 있으면 META 도 다시 쓰고 `updatedAt` 을 넣는다. 직원의 소속 변경은 직원의 변경이 아니다.
- **`saveUser`·`saveGroup` 은 META 를 강한 일관성 GetItem 으로 읽어 비교한다. `replaceWith` 는 GSI1 조회(ALL 프로젝션)에서 받은 저장본으로 비교한다**(스펙 §4).
- 멤버 줄의 쓰기 순서와 `addedAt` 보존(기존 `saveGroup` 주석의 불변식)은 바꾸지 않는다.
- **Gradle 규칙:** 서브에이전트는 자기 과제의 모듈 테스트만 포그라운드로 돌린다. 루트 `test`·`build`·`check`·`scaleTest` 전체는 돌리지 않는다. Gradle 을 둘 이상 동시에 돌리지 않는다.
- 커밋마다 푸시한다(`git push`). 커밋 트레일러: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## 스펙과 다르게 정한 것 (계획 작성 시 판정)

- **저장본을 아이템 맵 그대로 들지 않고 `Stored<T>(도메인 값, 키가 지금 규칙대로인가)` 로 줄여 든다.** 스펙 §3 은 "아이템 전체를 비교" 라고 적었다. 전체 동기화는 직원 10만 명의 저장본을 한꺼번에 들어야 하는데, `AttributeValue` 맵은 한 건에 1KB 를 넘게 먹는다. "도메인 값이 같고, 그 저장 아이템이 지금 규칙으로 만든 아이템과 같다" 는 "`updatedAt` 을 뺀 아이템 전체가 같다" 와 같은 판단이다 — 키 규칙이 바뀌면 둘째 조건이 거짓이 되어 다시 쓴다.

## Review Focus

1. **값이 없는 칸이 있는 직원·조직**(`email`·`displayName` 이 null, 조직 `displayName` null → GSI1 정렬키가 id) — 저장본에는 그 속성이 아예 없다. 같은 값으로 다시 저장하면 쓰지 않아야 한다(비교가 null 과 "속성 없음" 을 같게 봐야 한다). Task 1 이 테스트한다.
2. **SCIM 이 같은 값을 다시 보냄**(IdP 의 재전송, 같은 `active` PATCH) — 쓰기가 없고 `updatedAt` 도 그대로여야 한다. Task 1 이 저장소 수준에서 테스트한다.
3. **키 규칙이 예전 모양인 저장본**(GSI1 정렬키가 원문 대소문자) — 값이 같아도 다시 써서 키를 고쳐야 한다. Task 1(단건)과 Task 2(`replaceWith`)가 테스트한다.
4. **멤버만 바뀐 조직** — META 가 같아도 `updatedAt` 이 갱신되어야 한다. Task 1 과 Task 2 가 테스트한다.
5. **동기화 사이에 지워졌다가 다시 생긴 직원** — 저장본이 없으니 새로 쓴다. `replaceWith` 테스트의 "새 직원" 이 같은 경로를 탄다(Task 2).

## File Structure

| 파일 | 과제 | 책임 |
|---|---|---|
| `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` | 1·2 | `Stored<T>`, `saveUser`·`saveGroup` 비교 쓰기, `replaceWith` 비교 쓰기 |
| `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/WriteCounter.java` (신규) | 1 | PutItem 을 세는 클라이언트 감싸개(테스트 전용) |
| `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/GetCounter.java` (신규) | 2 | GetItem 을 세는 클라이언트 감싸개(테스트 전용) |
| `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java` | 1·2 | 단건·전체 교체 테스트 |
| `storage-dynamodb/build.gradle` | 3 | `testImplementation testFixtures(project(':core'))` (`@ScaleTest`) |
| `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ReplaceWithScaleTest.java` (신규) | 3 | 10만 명 규모 |
| `docs/superpowers/specs/2026-09-25-gsi1-hot-partition-design.md` §7 | 3 | 결과(컨트롤러) |

---

### Task 1: 단건 저장은 바뀐 것만 쓴다

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`saveUser`, `saveGroup`, 새 도우미)
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/WriteCounter.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Produces (패키지 전용, `DynamoDbDirectoryStateRepository` 안): `record Stored<T>(T value, boolean current)` 와 `boolean sameAs(T incoming)`; `private Stored<DirectoryUser> storedUser(Map<String, AttributeValue> item)`; `private Stored<GroupHeader> storedGroup(Map<String, AttributeValue> item)`; `private Mono<Void> writeUser(DirectoryUser user, Stored<DirectoryUser> stored)`; `private Mono<Void> writeGroup(DirectoryGroup group, Stored<GroupHeader> stored)` — `stored` 가 null 이면 저장본 없음
- Produces (테스트): `WriteCounter` — `DynamoDbAsyncClient wrap(DynamoDbAsyncClient real)`, `long puts()`, `void reset()`

- [ ] **Step 1: PutItem 계측을 만든다**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/WriteCounter.java`:

```java
package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 클라이언트를 감싸 PutItem 호출 수를 센다. "바뀌지 않았으면 쓰지 않는다" 를 호출 수로 단정하기 위한 계측이다 —
 * DynamoDB Local 은 GSI 쓰기 용량을 보여 주지 않으므로, PutItem 이 없다는 것까지가 테스트로 증명할 수 있는 선이다
 * (GSI 설계 §9).
 */
final class WriteCounter {

    private final AtomicLong puts = new AtomicLong();

    DynamoDbAsyncClient wrap(DynamoDbAsyncClient real) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putItem")) {
                        puts.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    long puts() {
        return puts.get();
    }

    void reset() {
        puts.set(0);
    }
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest` 에 추가한다. 이 파일에는 이미 `clock`(`MutableClock`, `앞으로(Duration)`)과 `repository` 필드, 멤버 줄의 `addedAt` 을 읽는 도우미가 있다. 아래 필드·도우미와 테스트를 더한다(`import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;`, `PutItemRequest`, `AttributeValue`, `java.util.HashMap`, `java.util.Map` 이 없으면 더한다):

```java
    /** META 의 updatedAt 을 직접 읽는다. 저장소 API 는 이 값을 노출하지 않는다. */
    private String updatedAt(String pk) {
        return meta(pk).get("updatedAt").s();
    }

    private Map<String, AttributeValue> meta(String pk) {
        return client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build())
                .join().item();
    }

    /** PutItem 을 세는 저장소. 같은 테이블·시계를 쓴다. */
    private DynamoDbDirectoryStateRepository 세는_저장소(WriteCounter counter) {
        return new DynamoDbDirectoryStateRepository(counter.wrap(client), properties, clock);
    }

    @Test
    @DisplayName("같은 직원을 다시 저장하면 쓰지 않고 updatedAt 도 첫 시각 그대로다")
    void 같은_직원은_다시_쓰지_않는다() {
        // given
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        DirectoryUser kim = new DirectoryUser("kim", "e1", "kim", "김철수", "kim@example.com", true);
        세는.saveUser(kim).block();
        String 처음 = updatedAt(Keys.userPk("kim"));
        counter.reset();
        clock.앞으로(Duration.ofHours(1));

        // when
        세는.saveUser(kim).block();

        // then
        assertThat(counter.puts()).isZero();
        assertThat(updatedAt(Keys.userPk("kim"))).isEqualTo(처음);
    }

    @Test
    @DisplayName("값이 없는 칸이 있는 직원도 같은 값이면 다시 쓰지 않는다")
    void 빈_칸이_있어도_같으면_쓰지_않는다() {
        // given — email·displayName 이 null 이면 저장본에는 그 속성이 아예 없다
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        DirectoryUser 빈칸 = new DirectoryUser("park", null, "park", null, null, false);
        세는.saveUser(빈칸).block();
        counter.reset();

        // when
        세는.saveUser(빈칸).block();

        // then
        assertThat(counter.puts()).isZero();
    }

    @Test
    @DisplayName("속성이 바뀌면 다시 쓰고 updatedAt 이 그 시각이 된다")
    void 바뀌면_updatedAt_이_그_시각이다() {
        // given
        repository.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        clock.앞으로(Duration.ofHours(1));

        // when
        repository.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, false)).block();

        // then
        assertThat(updatedAt(Keys.userPk("kim"))).isEqualTo("2026-01-01T01:00:00Z");
        assertThat(repository.findUser("kim").block().active()).isFalse();
    }

    @Test
    @DisplayName("키가 예전 규칙으로 저장돼 있으면 값이 같아도 다시 써서 키를 고친다")
    void 예전_키는_값이_같아도_고친다() {
        // given — GSI1 정렬키가 소문자가 되기 전(원문 대소문자)의 저장본을 흉내낸다
        DirectoryUser kim = new DirectoryUser("Kim", "e1", "Kim", "김철수", null, true);
        repository.saveUser(kim).block();
        Map<String, AttributeValue> 예전 = new HashMap<>(meta(Keys.userPk("Kim")));
        예전.put(Keys.GSI1SK, Attrs.s("Kim"));
        client.putItem(PutItemRequest.builder().tableName(properties.getTableName()).item(예전).build()).join();
        clock.앞으로(Duration.ofHours(1));

        // when
        repository.saveUser(kim).block();

        // then
        assertThat(meta(Keys.userPk("Kim")).get(Keys.GSI1SK).s()).isEqualTo("kim");
        assertThat(updatedAt(Keys.userPk("Kim"))).isEqualTo("2026-01-01T01:00:00Z");
    }

    @Test
    @DisplayName("조직은 META 와 멤버가 같으면 쓰지 않고, 멤버만 바뀌어도 updatedAt 이 갱신된다")
    void 조직은_멤버가_바뀌면_갱신된다() {
        // given
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        세는.saveUser(new DirectoryUser("kim", null, "kim", null, null, true)).block();
        세는.saveUser(new DirectoryUser("park", null, "park", null, null, true)).block();
        DirectoryGroup 개발팀 = new DirectoryGroup("DEV", "g1", "개발팀", Set.of(MemberRef.user("kim")));
        세는.saveGroup(개발팀).block();
        String 처음 = updatedAt(Keys.groupPk("DEV"));
        counter.reset();
        clock.앞으로(Duration.ofHours(1));

        // when — 같은 조직을 다시 저장한다
        세는.saveGroup(개발팀).block();

        // then — 아무것도 쓰지 않는다
        assertThat(counter.puts()).isZero();
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo(처음);

        // when — 멤버만 바꾼다
        clock.앞으로(Duration.ofHours(1));
        세는.saveGroup(new DirectoryGroup("DEV", "g1", "개발팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("park")))).block();

        // then — META 도 다시 쓰여 updatedAt 이 그 시각이 된다
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T02:00:00Z");
    }
```

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
Expected: `같은_직원은_다시_쓰지_않는다`·`빈_칸이_있어도_같으면_쓰지_않는다`·`조직은_멤버가_바뀌면_갱신된다` 가 FAIL(두 번째 저장도 PutItem 한다, `updatedAt` 이 바뀐다). 나머지 둘은 지금도 통과할 수 있다(항상 다시 쓰므로) — 그 둘은 구현 뒤에도 통과해야 하는 조건이다.

- [ ] **Step 4: 구현한다**

`DynamoDbDirectoryStateRepository` — `import java.util.Optional;` 을 더한다.

`saveUser` 를 다음으로 바꾸고, 기존 본문의 아이템 조립은 `userItem` 으로 옮기며 **`updatedAt` 줄은 뺀다**(주석은 그대로 옮긴다):

```java
    /**
     * 저장된 META 와 다를 때만 쓰고, 그때만 {@code updatedAt} 을 찍는다(GSI 설계 §3). 같은 값을 다시 쓰면 GSI1(ALL
     * 프로젝션)이 매번 {@code updatedAt} 때문에 다시 쓰여 {@code USER_INDEX} 한 파티션키로 몰렸다.
     *
     * <p>저장본은 <b>강한 일관성</b>으로 한 건 읽는다 — SCIM 요청 하나의 쓰기 경로라 한 건 더 읽어도 싸다.
     */
    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        return findMeta(Keys.userPk(user.id()))
                .map(this::storedUser)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(stored -> writeUser(user, stored.orElse(null)));
    }

    /** 저장본과 같으면 쓰지 않는다. 다르거나 없으면 {@code updatedAt} 을 찍어 쓴다. */
    private Mono<Void> writeUser(DirectoryUser user, Stored<DirectoryUser> stored) {
        if (stored != null && stored.sameAs(user)) {
            return Mono.empty();
        }
        return putItem(stamped(userItem(user)));
    }

    /** 직원 META 에 쓸 아이템. {@code updatedAt} 은 넣지 않는다 — 바뀌었을 때만 {@link #stamped} 가 넣는다. */
    private Map<String, AttributeValue> userItem(DirectoryUser user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.userPk(user.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.USER_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(Keys.indexKey(user.userName() == null ? user.id() : user.userName())));
        // (기존 saveUser 의 GSI2 주석을 여기로 옮긴다)
        item.put(ACTIVE, Attrs.bool(user.active()));
        Attrs.putIfPresent(item, EXTERNAL_ID, user.externalId());
        Attrs.putIfPresent(item, USER_NAME, user.userName());
        Attrs.putIfPresent(item, DISPLAY_NAME, user.displayName());
        Attrs.putIfPresent(item, EMAIL, user.email());
        return item;
    }
```

`saveGroup` 을 다음으로 바꾼다. 기존의 멤버 차이 계산·쓰기 순서·주석은 `writeGroup` 안으로 그대로 옮기고, META 쓰기만 조건부로 바꾼다:

```java
    /** 직원과 같은 규칙으로 쓴다. 조직의 변경은 META 또는 멤버 구성의 변경이다(GSI 설계 §3). */
    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        return findMeta(Keys.groupPk(group.id()))
                .map(this::storedGroup)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(stored -> writeGroup(group, stored.orElse(null)));
    }

    private Mono<Void> writeGroup(DirectoryGroup group, Stored<GroupHeader> stored) {
        GroupHeader header = new GroupHeader(group.id(), group.externalId(), group.displayName());
        Set<String> targetSks = group.members().stream().map(Keys::memberSk).collect(Collectors.toSet());

        return existingMemberSks(group.id())
                .collectList()
                .flatMap(existing -> {
                    Set<String> existingSks = Set.copyOf(existing);
                    List<String> 떠난멤버 = existing.stream()
                            .filter(sk -> !targetSks.contains(sk))
                            .toList();
                    // (기존 주석: 이미 있는 멤버는 건드리지 않는다 …)
                    List<MemberRef> 새로온멤버 = group.members().stream()
                            .filter(member -> !existingSks.contains(Keys.memberSk(member)))
                            .toList();

                    // 조직의 변경은 META 의 변경 또는 멤버 구성의 변경이다 — SCIM 의 Group 은 members 를 담는다
                    boolean 바뀜 = stored == null || !stored.sameAs(header)
                            || !떠난멤버.isEmpty() || !새로온멤버.isEmpty();
                    Mono<Void> meta = 바뀜 ? putItem(stamped(groupMeta(header))) : Mono.empty();

                    // (기존 주석: 소속 줄이 항상 멤버 줄보다 많거나 같게 유지한다 …)
                    return Flux.fromIterable(떠난멤버)
                            .map(Keys::parseMemberSk)
                            .flatMap(ref -> deleteItem(Keys.groupPk(group.id()), Keys.memberSk(ref))
                                    .then(deleteItem(Keys.memberPk(ref), Keys.belongsToSk(group.id()))),
                                    QUERY_CONCURRENCY)
                            .then(meta)
                            .then(Flux.fromIterable(새로온멤버)
                                    .flatMap(member -> putItem(belongsToItem(member, group.id()))
                                            .then(putItem(memberItem(group.id(), member))),
                                            QUERY_CONCURRENCY)
                                    .then());
                });
    }

    /** 조직 META 에 쓸 아이템. {@code updatedAt} 은 넣지 않는다. */
    private Map<String, AttributeValue> groupMeta(GroupHeader header) {
        Map<String, AttributeValue> meta = new HashMap<>();
        meta.put(Keys.PK, Attrs.s(Keys.groupPk(header.id())));
        meta.put(Keys.SK, Attrs.s(Keys.META));
        meta.put(Keys.GSI1PK, Attrs.s(Keys.GROUP_INDEX));
        meta.put(Keys.GSI1SK, Attrs.s(Keys.indexKey(header.displayName() == null ? header.id() : header.displayName())));
        Attrs.putIfPresent(meta, EXTERNAL_ID, header.externalId());
        Attrs.putIfPresent(meta, DISPLAY_NAME, header.displayName());
        return meta;
    }
```

그리고 공통 도우미(클래스의 "공통" 절 근처):

```java
    /**
     * 저장본을 도메인 값과 "그 아이템이 지금 규칙으로 만든 아이템과 같은가" 로 줄여 든다(GSI 설계 §3).
     *
     * <p>아이템 맵을 그대로 들지 않는 이유 — 전체 동기화는 직원 10만 명의 저장본을 한꺼번에 들고 비교하는데,
     * {@code AttributeValue} 맵은 한 건에 1KB 를 넘게 먹는다. "도메인 값이 같고 {@code current}" 는 "updatedAt 을 뺀
     * 아이템 전체가 같다" 와 같은 판단이다 — 키 규칙이 바뀌면 {@code current} 가 거짓이 되어 값이 같아도 다시 쓴다.
     */
    record Stored<T>(T value, boolean current) {

        boolean sameAs(T incoming) {
            return current && value.equals(incoming);
        }
    }

    private Stored<DirectoryUser> storedUser(Map<String, AttributeValue> item) {
        DirectoryUser user = toUser(Keys.parseUserPk(Attrs.str(item, Keys.PK)), item);
        return new Stored<>(user, sameContent(userItem(user), item));
    }

    private Stored<GroupHeader> storedGroup(Map<String, AttributeValue> item) {
        GroupHeader header = toGroupHeader(Keys.parseGroupPk(Attrs.str(item, Keys.PK)), item);
        return new Stored<>(header, sameContent(groupMeta(header), item));
    }

    /** {@code updatedAt} 을 뺀 저장 아이템이 쓰려는 아이템과 같은가. */
    private static boolean sameContent(Map<String, AttributeValue> expected, Map<String, AttributeValue> stored) {
        Map<String, AttributeValue> withoutStamp = new HashMap<>(stored);
        withoutStamp.remove(UPDATED_AT);
        return expected.equals(withoutStamp);
    }

    /** 바뀐 아이템에만 쓰는 시각. 이 값은 이제 "마지막 동기화" 가 아니라 "마지막 변경" 이다. */
    private Map<String, AttributeValue> stamped(Map<String, AttributeValue> item) {
        Map<String, AttributeValue> copy = new HashMap<>(item);
        copy.put(UPDATED_AT, Attrs.s(Instant.now(clock).toString()));
        return copy;
    }

    /** META 한 건을 <b>강한 일관성</b>으로 읽는다. 쓰기 전 비교용이다. */
    private Mono<Map<String, AttributeValue>> findMeta(String pk) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(GetItemResponse::item);
    }
```

`UPDATED_AT` 상수의 뜻이 바뀌었으니 상수 위에 한 줄 주석을 단다: `/** 마지막 <b>변경</b> 시각. 바뀐 META 에만 찍는다(GSI 설계 §3). */`

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test`
Expected: 전부 PASS (기존 `addedAt` 보존·멤버 쓰기 순서 테스트 포함)

- [ ] **Step 6: 커밋하고 푸시한다**

```bash
git add storage-dynamodb
git commit -m "feat: 직원·조직 META 는 바뀌었을 때만 쓰고 updatedAt 을 진짜 변경 시각으로" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 2: 전체 교체도 바뀐 것만 쓴다

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`replaceWith`, 새 `storedIndex`)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: `Stored<T>`, `storedUser`, `storedGroup`, `writeUser`, `writeGroup` (Task 1), `WriteCounter` (Task 1)
- Produces: 없음(포트 시그니처는 그대로)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest` 에 추가(`DirectorySnapshot`, `LinkedHashMap`, `java.util.stream.IntStream` import):

```java
    /** 직원 n명(u000…)과 조직 셋 — 조직 G1 은 u000·u001, G2 는 이름 없는 조직, G3 은 멤버 없는 조직. */
    private static DirectorySnapshot 조직도(int n) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String id = "u%03d".formatted(i);
            users.put(id, new DirectoryUser(id, "ext-" + id, id, "직원 " + i, i % 2 == 0 ? null : id + "@example.com", true));
        }
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        groups.put("G1", new DirectoryGroup("G1", "g1", "개발팀", Set.of(MemberRef.user("u000"), MemberRef.user("u001"))));
        groups.put("G2", new DirectoryGroup("G2", "g2", null, Set.of(MemberRef.user("u002"))));
        groups.put("G3", new DirectoryGroup("G3", "g3", "빈 조직", Set.of()));
        return new DirectorySnapshot(users, groups);
    }

    @Test
    @DisplayName("같은 조직도로 전체 교체를 다시 하면 아무것도 쓰지 않는다")
    void 같은_조직도는_다시_쓰지_않는다() {
        // given
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        세는.replaceWith(조직도(50)).block();
        counter.reset();

        // when
        세는.replaceWith(조직도(50)).block();

        // then
        assertThat(counter.puts()).isZero();
    }

    @Test
    @DisplayName("일부만 바뀌면 그만큼만 쓰고 그것만 updatedAt 이 바뀐다")
    void 바뀐_만큼만_쓴다() {
        // given
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        세는.replaceWith(조직도(50)).block();
        counter.reset();
        clock.앞으로(Duration.ofHours(1));

        DirectorySnapshot 바뀐 = 조직도(50);
        Map<String, DirectoryUser> users = new LinkedHashMap<>(바뀐.users());
        for (int i = 10; i < 15; i++) {
            String id = "u%03d".formatted(i);
            users.put(id, new DirectoryUser(id, "ext-" + id, id, "이름 바뀜 " + i, null, true));
        }
        users.put("new", new DirectoryUser("new", "ext-new", "new", "새 직원", null, true));
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>(바뀐.groups());
        groups.put("G3", new DirectoryGroup("G3", "g3", "빈 조직", Set.of(MemberRef.user("u003"))));

        // when
        세는.replaceWith(new DirectorySnapshot(users, groups)).block();

        // then — 직원 5명 + 새 직원 1명 + 조직 G3 (META 1 + 소속 줄 1 + 멤버 줄 1)
        assertThat(counter.puts()).isEqualTo(5 + 1 + 3);
        assertThat(updatedAt(Keys.userPk("u010"))).isEqualTo("2026-01-01T01:00:00Z");
        assertThat(updatedAt(Keys.userPk("u020"))).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(updatedAt(Keys.groupPk("G3"))).isEqualTo("2026-01-01T01:00:00Z");
        assertThat(updatedAt(Keys.groupPk("G1"))).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("전체 교체에서도 키가 예전 규칙인 저장본은 값이 같아도 다시 쓴다")
    void 전체_교체도_예전_키를_고친다() {
        // given
        DirectorySnapshot 조직도 = 조직도(3);
        repository.replaceWith(조직도).block();
        Map<String, AttributeValue> 예전 = new HashMap<>(meta(Keys.userPk("u001")));
        예전.put(Keys.GSI1SK, Attrs.s("U001"));
        client.putItem(PutItemRequest.builder().tableName(properties.getTableName()).item(예전).build()).join();

        // when
        repository.replaceWith(조직도).block();

        // then
        assertThat(meta(Keys.userPk("u001")).get(Keys.GSI1SK).s()).isEqualTo("u001");
    }
```

같은 파일에 다음 테스트도 더한다. Task 1 뒤의 `replaceWith` 는 `saveUser`·`saveGroup` 을 거쳐 이미 PutItem 을 하지 않지만 **직원·조직마다 저장본을 GetItem 으로 한 번씩 읽는다**(10만 명이면 10만 번). 이 과제의 목적은 그 읽기를 GSI1 조회 한 번으로 바꾸는 것이다:

```java
    @Test
    @DisplayName("전체 교체는 직원·조직마다 저장본을 따로 읽지 않는다 — GSI1 을 훑은 결과로 비교한다")
    void 전체_교체는_하나씩_읽지_않는다() {
        // given
        GetCounter gets = new GetCounter();
        var 세는 = new DynamoDbDirectoryStateRepository(gets.wrap(client), properties, clock);
        세는.replaceWith(조직도(50)).block();
        gets.reset();

        // when
        세는.replaceWith(조직도(50)).block();

        // then
        assertThat(gets.gets()).isZero();
    }
```

`GetCounter` 는 `WriteCounter` 와 같은 모양으로 `getItem` 호출을 센다 — `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/GetCounter.java`:

```java
package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

/** 클라이언트를 감싸 GetItem 호출 수를 센다. 전체 교체가 직원·조직마다 저장본을 따로 읽지 않는지 단정한다. */
final class GetCounter {

    private final AtomicLong gets = new AtomicLong();

    DynamoDbAsyncClient wrap(DynamoDbAsyncClient real) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getItem")) {
                        gets.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    long gets() {
        return gets.get();
    }

    void reset() {
        gets.set(0);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
Expected: `전체_교체는_하나씩_읽지_않는다` 가 FAIL(GetItem 이 직원 50 + 조직 3 번). 나머지 셋은 Task 1 덕분에 이미 통과할 수 있다 — 구현 뒤에도 통과해야 하는 조건이다.

- [ ] **Step 3: 구현한다**

`replaceWith` 를 다음으로 바꾼다:

```java
    /**
     * LDAP 전체 동기화·재적재. 삭제 판단 때문에 원래 GSI1 을 훑던 조회에서 <b>저장본을 함께</b> 받아(GSI1 은 ALL
     * 프로젝션이라 더 읽지 않는다) 비교하고, 새로 생기거나 바뀐 것만 쓴다(GSI 설계 §4).
     *
     * <p><b>비교 기준이 최종 일관성 인덱스다.</b> 이 메서드에 내용을 넣어 부르는 것은 LDAP 의 전체 동기화와 재적재뿐이고,
     * LDAP 앱에는 그와 동시에 직원·조직을 쓰는 경로가 없다 — 인덱스는 이전 회차 뒤로 이미 맞춰져 있다. 늦은 인덱스가 부를
     * 수 있는 일은 "같은데 다르다고 보고 한 번 더 쓰기" 로, 무해하다.
     */
    @Override
    public Mono<Void> replaceWith(DirectorySnapshot snapshot) {
        return Mono.zip(
                        storedIndex(Keys.USER_INDEX, this::storedUser, DirectoryUser::id),
                        storedIndex(Keys.GROUP_INDEX, this::storedGroup, GroupHeader::id))
                .flatMap(stored -> {
                    Map<String, Stored<DirectoryUser>> users = stored.getT1();
                    Map<String, Stored<GroupHeader>> groups = stored.getT2();

                    Mono<Void> removeStaleUsers = Flux.fromIterable(users.keySet())
                            .filter(id -> !snapshot.users().containsKey(id))
                            .flatMap(this::deleteUser, QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> removeStaleGroups = Flux.fromIterable(groups.keySet())
                            .filter(id -> !snapshot.groups().containsKey(id))
                            .flatMap(this::deleteGroup, QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> upsertUsers = Flux.fromIterable(snapshot.users().values())
                            .flatMap(user -> writeUser(user, users.get(user.id())), QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> upsertGroups = Flux.fromIterable(snapshot.groups().values())
                            .flatMap(group -> writeGroup(group, groups.get(group.id())), QUERY_CONCURRENCY)
                            .then();

                    return removeStaleUsers.then(removeStaleGroups).then(upsertUsers).then(upsertGroups);
                });
    }

    /** GSI1 파티션 하나를 훑어 id → 저장본. 삭제 판단과 변경 비교를 한 번의 조회로 한다. */
    private <T> Mono<Map<String, Stored<T>>> storedIndex(String indexPartition,
                                                        Function<Map<String, AttributeValue>, Stored<T>> toStored,
                                                        Function<T, String> idOf) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(indexPartition)))
                .build();
        return Paginator.queryAll(client, request)
                .map(toStored)
                .collectMap(stored -> idOf.apply(stored.value()), stored -> stored);
    }
```

`enumerateIds` 는 `loadAll` 이 아직 쓰므로 둔다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test`
Expected: 전부 PASS (기존 `replaceWith` 삭제 테스트 포함)

- [ ] **Step 5: 커밋하고 푸시한다**

```bash
git add storage-dynamodb
git commit -m "feat: 전체 교체도 GSI1 을 훑은 저장본과 비교해 바뀐 것만 쓴다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 3: 10만 명 규모 테스트

**Files:**
- Modify: `storage-dynamodb/build.gradle`
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ReplaceWithScaleTest.java`
- Modify: `docs/superpowers/specs/2026-09-25-gsi1-hot-partition-design.md` §7 (컨트롤러)

**Interfaces:**
- Consumes: `WriteCounter` (Task 1), `DynamoDbTestSupport`, `@ScaleTest`(core testFixtures)

**규모 테스트는 서브에이전트가 돌리지 않는다.** 구현자는 `./gradlew :storage-dynamodb:compileTestJava` 까지 확인하고, 실행과 §7 기록은 컨트롤러가 한다.

- [ ] **Step 1: 테스트 의존성을 더한다**

`storage-dynamodb/build.gradle` 의 `dependencies` 에:

```groovy
    // @ScaleTest(core testFixtures) — 10만 명 전체 교체 규모 테스트가 쓴다
    testImplementation testFixtures(project(':core'))
```

- [ ] **Step 2: 규모 테스트를 쓴다**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ReplaceWithScaleTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 직원 10만 명의 전체 교체 (GSI 설계 §6). 실제 운영 규모가 10만 명 이상이라, 매 동기화가 GSI1 한 파티션키로 몰아
 * 쓰던 양이 변경 수로 줄었는지를 PutItem 수로 단정한다. 변경은 저장소 한 곳이라 LDAP 서버 없이 저장소 수준에서 본다.
 */
@ScaleTest
class ReplaceWithScaleTest extends DynamoDbTestSupport {

    private static final int 전체 = 100_000;

    private static DirectorySnapshot 조직도(Map<String, DirectoryUser> 바뀐직원) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        for (int i = 0; i < 전체; i++) {
            String id = "u%06d".formatted(i);
            users.put(id, new DirectoryUser(id, "ext-" + id, id, "직원 " + i, null, true));
        }
        users.putAll(바뀐직원);
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        for (int g = 0; g < 100; g++) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (int m = 0; m < 10; m++) {
                members.add(MemberRef.user("u%06d".formatted(g * 10 + m)));
            }
            groups.put("G%03d".formatted(g), new DirectoryGroup("G%03d".formatted(g), "g" + g, "조직 " + g, members));
        }
        return new DirectorySnapshot(users, groups);
    }

    @Test
    @DisplayName("10만 명을 적재한 뒤 같은 조직도는 PutItem 0번, 100명을 바꾸면 PutItem 100번이다")
    void 바뀐_만큼만_쓴다() {
        // given
        WriteCounter counter = new WriteCounter();
        var repository = new DynamoDbDirectoryStateRepository(counter.wrap(client), properties, Clock.systemUTC());
        long 시작 = System.currentTimeMillis();
        repository.replaceWith(조직도(Map.of())).block(Duration.ofMinutes(30));
        long 적재 = System.currentTimeMillis() - 시작;
        long 적재쓰기 = counter.puts();

        // when — 같은 조직도
        counter.reset();
        시작 = System.currentTimeMillis();
        repository.replaceWith(조직도(Map.of())).block(Duration.ofMinutes(30));
        long 같음 = System.currentTimeMillis() - 시작;
        long 같음쓰기 = counter.puts();

        // when — 100명만 바뀜
        Map<String, DirectoryUser> 바뀐직원 = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            String id = "u%06d".formatted(i * 1_000);
            바뀐직원.put(id, new DirectoryUser(id, "ext-" + id, id, "이름 바뀜 " + i, null, true));
        }
        counter.reset();
        시작 = System.currentTimeMillis();
        repository.replaceWith(조직도(바뀐직원)).block(Duration.ofMinutes(30));
        long 일부 = System.currentTimeMillis() - 시작;
        long 일부쓰기 = counter.puts();

        // then
        System.out.printf("적재: %,dms PutItem %,d / 같은 조직도: %,dms PutItem %,d / 100명 변경: %,dms PutItem %,d%n",
                적재, 적재쓰기, 같음, 같음쓰기, 일부, 일부쓰기);
        assertThat(같음쓰기).isZero();
        assertThat(일부쓰기).isEqualTo(100);
    }
}
```

- [ ] **Step 3: 컴파일을 확인한다 (구현자)**

Run: `./gradlew :storage-dynamodb:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋하고 푸시한다 (구현자)**

```bash
git add storage-dynamodb
git commit -m "test: 10만 명 전체 교체 — 같은 조직도는 PutItem 0, 100명 변경은 100" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

- [ ] **Step 5: 실행하고 결과를 기록한다 (컨트롤러)**

Run: `./gradlew :storage-dynamodb:cleanScaleTest :storage-dynamodb:scaleTest --tests '*ReplaceWithScaleTest'`
Expected: PASS. 출력 줄(적재·같은 조직도·100명 변경의 시간과 PutItem 수)을 스펙 §7 에 적고 커밋·푸시한다.

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 **둘 다** 돌리고 소요 시간을 스펙 §7 에 적는다. Gradle 은 하나씩.
- 사용자에게 결과를 보고하고 머지 여부를 묻는다.

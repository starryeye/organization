# 권한 정합성 급한 불 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 치명 C1(스냅샷 만료로 삭제 누락)·C4(작은따옴표 아이디 remove 불가)·C8(Check 캐시)·C5(LDAP 타임아웃)를 고친다.

**Architecture:** C1 은 스냅샷 저장소에서 TTL 을 떼고 정리 작업이 최신을 건너뛰게 하며, 메타를 먼저 쓰고, 기준선이 깨졌으면 `SnapshotIntegrityException` 으로
회차를 멈춘다. C4 는 멤버 빼기 필터의 대괄호 안을 목록 조회와 같은 `ScimFilter` 로 읽는다. C8 은 Check·BatchCheck 에 `HIGHER_CONSISTENCY` 를 넘긴다.
C5 는 `LdapContextSource` 에 JNDI 연결·읽기 타임아웃을 싣는다. 네 과제는 서로 코드를 공유하지 않는다.

**Tech Stack:** Java 17, Spring Boot 3 / WebFlux, Reactor, AWS SDK v2 DynamoDB(비동기), OpenFGA Java SDK 0.9.11, Spring LDAP, JUnit 5, AssertJ, Testcontainers
(DynamoDB Local 2.5.3, OpenFGA v1.10.2), Lombok.

**Spec:** `docs/superpowers/specs/2026-09-29-integrity-quick-fixes-design.md`

## Global Constraints

- 테스트: Lombok, AssertJ, BDD(`// given` `// when` `// then`), 한글 `@DisplayName`, 한글 메서드 이름. 주변 코드의 주석 밀도·문체(평서형 "~다")를 따른다.
- 각 테스트는 **먼저 지금 코드로 실패하는 것을 보고** 고친다(계약 고정 테스트는 과제에 따로 적은 변이 확인으로 대신한다).
- OpenFGA Read API 는 쓰지 않는다. Check·BatchCheck 는 허용.
- 운영 배포 전이라 기존 데이터 이관·하위호환을 만들지 않는다.
- 서브에이전트(구현자)는 **자기 모듈의 빠른 테스트만** 돌린다(`./gradlew :<module>:test`). 전체 `test`·`scaleTest` 는 컨트롤러가 돌린다. Gradle 은 한 번에 하나만.
- 커밋마다 `git push`(브랜치 `audit-fix-integrity`, 업스트림 설정돼 있음). 커밋 메시지 끝에 attribution 한 줄.
- 서브에이전트를 띄우지 않는다.

## Review Focus

1. **메타를 먼저 쓴 뒤 곧바로 읽을 때 최종 일관성 때문에 튜플 수가 모자라 보이는 거짓 오류** — 스냅샷 파티션 Query 는 강한 일관성이어야 한다(Task 1 Step 6 테스트: 모든 Query 가 `consistentRead=true`).
2. **정리 작업이 포인터를 못 읽었을 때 "최신 없음"으로 보고 최신까지 지우는 것** — 포인터 읽기 오류는 정리 전체를 멈춰야 한다(Task 1 Step 6 테스트).
3. **튜플 0개짜리 스냅샷**(빈 디렉터리) — 정상 기준선으로 읽혀야 하고 무결성 오류가 아니다(Task 1 Step 6 테스트).
4. **`members[value eq ""]`** — `IdNormalizer` 가 던지는 `IllegalArgumentException` 이 500 으로 새지 않고 400 이어야 한다(Task 2 테스트).
5. **대괄호 속성 이름 대소문자·값 안의 `]`** — `Members[value eq "kim"]`, `members[value eq "x]y"]` 가 맞게 풀려야 한다(Task 2 테스트).

---

### Task 1: C1 — 최신 스냅샷은 지우지 않고, 기준선이 깨지면 회차를 멈춘다

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/port/SnapshotIntegrityException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/TupleSnapshotRepository.java` (자바독)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSnapshotRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java`
- Modify: `README.md` (§ "스냅샷이 왜 있는가" 끝, 113행 근처 TTL 문단)

**Interfaces:**
- Produces: `dev.starryeye.organization.core.port.SnapshotIntegrityException extends RuntimeException` (생성자 `(String message)`).
- Produces: `FakeSnapshotRepository#failFindLatest(RuntimeException error)`.
- 저장소 규칙: `findLatest()` — 포인터 없으면 빈 Mono, 포인터가 가리키는 스냅샷의 메타가 없거나 튜플 수가 메타와 다르면 `SnapshotIntegrityException`.
  `findById(id)` — 메타 없으면 빈 Mono, 튜플 수 불일치면 `SnapshotIntegrityException`. `purgeExpired()` — 최신 포인터 대상은 지우지 않는다.

- [ ] **Step 1: 예외와 가짜 저장소**

`core/src/main/java/dev/starryeye/organization/core/port/SnapshotIntegrityException.java`:

```java
package dev.starryeye.organization.core.port;

/**
 * 최신 포인터가 가리키는 튜플 스냅샷을 온전히 읽지 못했다 — 기준선이 깨졌다.
 *
 * <p>빈 기준선으로 넘어가면 그 회차의 삭제를 하나도 하지 않고 SUCCEEDED 가 된다(점검 C1). 그래서 빈 결과가 아니라
 * 이 예외로 회차를 멈추고, 메시지로 복구 방법을 알린다.
 */
public class SnapshotIntegrityException extends RuntimeException {

    public SnapshotIntegrityException(String message) {
        super(message);
    }
}
```

`FakeSnapshotRepository` 에 필드·메서드를 더하고 `findLatest` 를 바꾼다:

```java
    private RuntimeException findLatestError;

    /** 기준선이 깨진 저장소를 흉내 낸다 — findLatest 가 이 오류로 끝난다. */
    public void failFindLatest(RuntimeException error) {
        this.findLatestError = error;
    }

    @Override
    public Mono<TupleSnapshot> findLatest() {
        if (findLatestError != null) {
            return Mono.error(findLatestError);
        }
        return saved.isEmpty() ? Mono.empty() : Mono.just(saved.get(saved.size() - 1));
    }
```

`TupleSnapshotRepository` 자바독을 고친다(시그니처는 그대로):

```java
    /**
     * 포인터가 없으면 빈 Mono(처음 설치·재적재 직후). 포인터가 가리키는 스냅샷의 메타가 없거나 튜플 수가 메타와 다르면
     * {@link SnapshotIntegrityException} — 빈 기준선으로 넘어가면 삭제를 조용히 놓친다.
     */
    Mono<TupleSnapshot> findLatest();

    /** 메타 → 튜플 → 포인터 순으로 저장한다. 메타가 먼저라 중간에 죽어도 정리 대상이고, 포인터가 마지막이라 반쪽이 기준선이 되지 않는다. */
    Mono<Void> save(TupleSnapshot snapshot);

    /** 메타가 없으면 빈 Mono. 튜플 수가 메타와 다르면 {@link SnapshotIntegrityException}. */
    Mono<TupleSnapshot> findById(String snapshotId);

    /**
     * 보존 기간이 지난 스냅샷을 지운다. 최신 포인터가 가리키는 스냅샷은 기간과 상관없이 지우지 않는다 — 비교 기준이다.
     * 스냅샷은 테이블 TTL 을 쓰지 않으므로 이 정리가 유일한 삭제 경로다. 삭제한 스냅샷 수를 반환한다.
     */
    Mono<Integer> purgeExpired();
```

- [ ] **Step 2: 저장소 실패 테스트를 쓴다**

`DynamoDbTupleSnapshotRepositoryTest` 에 import 를 더한다: `dev.starryeye.organization.core.port.SnapshotIntegrityException`,
`software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient`, `software.amazon.awssdk.services.dynamodb.model.AttributeValue`,
`software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest`, `software.amazon.awssdk.services.dynamodb.model.QueryRequest`,
`java.lang.reflect.Proxy`, `java.util.ArrayList`, `java.util.List`, `java.util.Map`, `java.util.concurrent.CompletableFuture`,
`static org.assertj.core.api.Assertions.assertThatThrownBy`. 그리고 도우미와 테스트를 더한다:

```java
    /** 이 저장소가 쓴 한 스냅샷 파티션의 원본 아이템(강한 일관성). */
    private List<Map<String, AttributeValue>> 파티션(String snapshotId) {
        return client.query(QueryRequest.builder()
                        .tableName(properties.getTableName())
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(Keys.snapshotPk(snapshotId))))
                        .consistentRead(true)
                        .build()).join().items();
    }

    private void 아이템을_지운다(String snapshotId, String sk) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, AttributeValue.fromS(Keys.snapshotPk(snapshotId)), Keys.SK, AttributeValue.fromS(sk)))
                .build()).join();
    }

    /** 지정한 호출만 실패시키고 나머지는 진짜 클라이언트로 보낸다. 보낸 Query 요청은 기록한다. */
    private DynamoDbAsyncClient 가로채는_클라이언트(String 실패시킬_메서드, List<QueryRequest> 보낸_Query) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("query") && args != null && args[0] instanceof QueryRequest request) {
                        보낸_Query.add(request);
                    }
                    if (method.getName().equals(실패시킬_메서드)) {
                        return CompletableFuture.failedFuture(new IllegalStateException(실패시킬_메서드 + " 실패(테스트)"));
                    }
                    return method.invoke(client, args);
                });
    }

    @Test
    @DisplayName("최신 스냅샷은 보존 기간이 지나도 정리하지 않는다 — 비교 기준이다")
    void 최신은_보존_기간이_지나도_정리하지_않는다() {
        // given — 8일 동안 변경 없음·가드 중단·실패만 있어 새 스냅샷이 안 쓰였다. 최신 = 8일 전 것(보존 7일)
        var 최신 = new TupleSnapshot("20260806T030000-LDAP", 지금.minusSeconds(8 * 86400), SyncSource.LDAP, 튜플들(4));
        repository.saveWithCreatedAt(최신).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isZero();
        assertThat(repository.findLatest().block().tuples()).hasSize(4);
    }

    @Test
    @DisplayName("보존 기간이 지난 스냅샷 중 최신만 남기고 나머지는 정리한다")
    void 지난_것_중_최신만_남긴다() {
        // given — 10일 전 A, 8일 전 B(최신). 둘 다 보존 기간(7일)을 넘겼다
        repository.saveWithCreatedAt(new TupleSnapshot("20260804T030000-LDAP", 지금.minusSeconds(10 * 86400), SyncSource.LDAP, 튜플들(2))).block();
        repository.saveWithCreatedAt(new TupleSnapshot("20260806T030000-LDAP", 지금.minusSeconds(8 * 86400), SyncSource.LDAP, 튜플들(3))).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(repository.findById("20260804T030000-LDAP").blockOptional()).isEmpty();
        assertThat(repository.findLatest().block().id()).isEqualTo("20260806T030000-LDAP");
    }

    @Test
    @DisplayName("스냅샷 아이템에는 테이블 TTL(expiresAt)이 없다 — DynamoDB 가 최신을 알아서 지우지 못한다")
    void 스냅샷_아이템에는_TTL이_없다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        var items = 파티션("20260814T030000-LDAP");

        // then — 메타 1 + 튜플 3, 아무도 expiresAt 을 갖지 않고 메타만 보관 기한을 갖는다
        assertThat(items).hasSize(4);
        assertThat(items).allSatisfy(item -> assertThat(item).doesNotContainKey(Keys.EXPIRES_AT));
        assertThat(items).filteredOn(item -> Keys.META.equals(item.get(Keys.SK).s()))
                .singleElement()
                .satisfies(meta -> assertThat(meta.get("retainUntil").n())
                        .isEqualTo(String.valueOf(지금.plusSeconds(7 * 86400).getEpochSecond())));
    }

    @Test
    @DisplayName("메타를 먼저 쓴다 — 튜플 쓰기가 실패해도 메타가 있어 정리 대상이고, 포인터는 직전 스냅샷 그대로다")
    void 메타를_먼저_쓴다() {
        // given — 직전 스냅샷 S1 이 최신이다. S2 를 저장하다 튜플 쓰기(BatchWriteItem)가 실패한다
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        var 실패하는_저장소 = new DynamoDbTupleSnapshotRepository(
                가로채는_클라이언트("batchWriteItem", new ArrayList<>()), properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        assertThatThrownBy(() -> 실패하는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block());

        // then — S2 의 메타는 목록에 있고(정리 작업이 찾는다), 포인터는 S1 이다
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .contains("20260814T030000-LDAP");
        assertThat(repository.findLatest().block().id()).isEqualTo("20260813T030000-LDAP");

        // and — 보존 기간 뒤 정리하면 반쪽 S2 는 지워지고 최신 S1 은 남는다
        var 여드레_뒤 = new DynamoDbTupleSnapshotRepository(client, properties,
                Clock.fixed(지금.plusSeconds(8 * 86400), ZoneOffset.UTC));
        assertThat(여드레_뒤.purgeExpired().block()).isEqualTo(1);
        assertThat(파티션("20260814T030000-LDAP")).isEmpty();
        assertThat(여드레_뒤.findLatest().block().id()).isEqualTo("20260813T030000-LDAP");
    }

    @Test
    @DisplayName("포인터는 있는데 그 스냅샷의 메타가 없으면 빈 기준선이 아니라 오류다")
    void 메타가_없으면_오류다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();
        아이템을_지운다("20260814T030000-LDAP", Keys.META);

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("mode=store");
    }

    @Test
    @DisplayName("읽은 튜플 수가 메타와 다르면 오류다 — 반쪽 기준선으로 삭제를 놓치지 않는다")
    void 튜플_수가_다르면_오류다() {
        // given — 튜플 4개 중 하나가 사라졌다
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();
        String 튜플_키 = 파티션("20260814T030000-LDAP").stream()
                .map(item -> item.get(Keys.SK).s())
                .filter(sk -> !Keys.META.equals(sk))
                .findFirst().orElseThrow();
        아이템을_지운다("20260814T030000-LDAP", 튜플_키);

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("메타 튜플 4 · 읽음 3");
    }

    @Test
    @DisplayName("튜플이 하나도 없는 스냅샷도 정상 기준선이다")
    void 빈_스냅샷도_정상_기준선이다() {
        // given — 조직도가 비어 튜플이 0개인 회차
        repository.save(스냅샷("20260814T030000-LDAP", 지금, Set.of())).block();

        // when
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260814T030000-LDAP");
        assertThat(latest.tuples()).isEmpty();
    }

    @Test
    @DisplayName("포인터를 못 읽으면 정리는 아무것도 지우지 않고 실패한다 — 최신 없음으로 보고 최신까지 지우지 않는다")
    void 포인터를_못_읽으면_정리하지_않는다() {
        // given — 보존 기간이 지난 최신 하나
        repository.saveWithCreatedAt(new TupleSnapshot("20260806T030000-LDAP", 지금.minusSeconds(8 * 86400), SyncSource.LDAP, 튜플들(2))).block();
        var 포인터가_안_읽히는_저장소 = new DynamoDbTupleSnapshotRepository(
                가로채는_클라이언트("getItem", new ArrayList<>()), properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when, then
        assertThatThrownBy(() -> 포인터가_안_읽히는_저장소.purgeExpired().block());
        assertThat(파티션("20260806T030000-LDAP")).hasSize(3);
    }

    @Test
    @DisplayName("스냅샷 파티션은 강한 일관성으로 읽는다 — 저장 직후 읽어도 튜플 수가 모자라 보이지 않는다")
    void 스냅샷_파티션은_강한_일관성으로_읽는다() {
        // given
        List<QueryRequest> 보낸_Query = new ArrayList<>();
        var 기록하는_저장소 = new DynamoDbTupleSnapshotRepository(
                가로채는_클라이언트("없음", 보낸_Query), properties, Clock.fixed(지금, ZoneOffset.UTC));
        기록하는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        기록하는_저장소.findLatest().block();

        // then — GSI 가 아닌(본 테이블) Query 는 전부 consistentRead
        assertThat(보낸_Query).filteredOn(request -> request.indexName() == null)
                .isNotEmpty()
                .allSatisfy(request -> assertThat(request.consistentRead()).isTrue());
    }
```

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests dev.starryeye.organization.storage.DynamoDbTupleSnapshotRepositoryTest`
Expected: 새 테스트 대부분 FAIL. 특히 `최신은_보존_기간이_지나도_정리하지_않는다`(purged 1), `스냅샷_아이템에는_TTL이_없다`(expiresAt 있음),
`메타를_먼저_쓴다`(목록에 S2 없음), `메타가_없으면_오류다`(빈 결과), `스냅샷_파티션은_강한_일관성으로_읽는다`(consistentRead null).
`빈_스냅샷도_정상_기준선이다` 는 지금도 통과할 수 있다(계약 고정). 컴파일 오류가 아니라 단언 실패여야 한다.

- [ ] **Step 4: 저장소를 고친다**

`DynamoDbTupleSnapshotRepository`:

클래스 자바독 첫 문단을 바꾼다:

```java
/**
 * OpenFGA 에 실제로 반영된 튜플의 기록.
 *
 * <p>저장 순서는 메타 → 튜플 → 포인터다. 메타가 먼저라 튜플을 쓰다 죽어도 정리 작업이 그 조각을 찾아 지우고,
 * 포인터가 마지막이라 반쪽 스냅샷이 기준선이 되지 않는다.
 *
 * <p><b>스냅샷은 테이블 TTL({@link Keys#EXPIRES_AT})을 쓰지 않는다.</b> TTL 은 아이템마다 붙은 시각만 보고 지워 최신인지
 * 모른다 — 최신(비교 기준)까지 지워지면 다음 회차가 빈 기준선으로 돌아 삭제를 하나도 안 한다(점검 C1). 보관 기한은 메타의
 * {@code retainUntil} 에만 적고, {@link #purgeExpired()} 가 최신을 건너뛰며 지운다.
 */
```

상수 하나를 더한다: `private static final String RETAIN_UNTIL = "retainUntil";`

저장 부분을 바꾼다:

```java
    /** 테스트에서 과거 시각의 스냅샷을 만들기 위한 변형. 보관 기한을 snapshot.createdAt 기준으로 잡는다. */
    public Mono<Void> saveWithCreatedAt(TupleSnapshot snapshot) {
        return doSave(snapshot, snapshot.createdAt());
    }

    private Mono<Void> doSave(TupleSnapshot snapshot, Instant retentionBase) {
        long retainUntil = retentionBase.plus(Duration.ofDays(properties.getSnapshotRetentionDays())).getEpochSecond();

        return writeMeta(snapshot, retainUntil)
                .then(writeTuples(snapshot))
                .then(writePointer(snapshot.id()));
    }

    private Mono<Void> writeTuples(TupleSnapshot snapshot) {
        return Flux.fromIterable(snapshot.tuples())
                .map(tuple -> WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(tupleItem(snapshot.id(), tuple)).build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(this::batchWrite)
                .then();
    }

    private Map<String, AttributeValue> tupleItem(String snapshotId, RelationTuple tuple) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)));
        item.put(Keys.SK, Attrs.s(Keys.tupleSk(tuple)));
        return item;
    }
```

`writeMeta(TupleSnapshot snapshot, long retainUntil)` 에서 `item.put(Keys.EXPIRES_AT, Attrs.n(expiresAt));` 를
`item.put(RETAIN_UNTIL, Attrs.n(retainUntil));` 로 바꾼다.

읽기 부분을 바꾼다:

```java
    @Override
    public Mono<TupleSnapshot> findLatest() {
        return latestId().flatMap(id -> findById(id)
                .switchIfEmpty(Mono.error(() -> new SnapshotIntegrityException(
                        "기준선 스냅샷 %s 의 메타가 없습니다 — POST /admin/sync/rebuild?mode=store 로 복구하세요".formatted(id)))));
    }

    /** 최신 포인터가 가리키는 스냅샷 id. 강한 일관성으로 읽는다 — 정리 작업이 이 값으로 최신을 건너뛴다. 포인터가 없으면 빈 Mono. */
    private Mono<String> latestId() {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .consistentRead(true)
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> Attrs.str(response.item(), SNAPSHOT_ID));
    }
```

`toSnapshot` 에서 튜플을 모은 뒤, `return new TupleSnapshot(...)` 앞에 넣는다:

```java
        int expected = Attrs.integer(meta, TUPLE_COUNT);
        if (tuples.size() != expected) {
            throw new SnapshotIntegrityException(
                    "스냅샷 %s 를 온전히 읽지 못했습니다(메타 튜플 %d · 읽음 %d) — POST /admin/sync/rebuild?mode=store 로 복구하세요"
                            .formatted(snapshotId, expected, tuples.size()));
        }
```

`snapshotIndexItems` 자바독의 "`expiresAt` 을 포함한" 을 "`retainUntil` 을 포함한" 으로 고친다.

정리를 바꾼다(자바독 첫 문단도 교체):

```java
    /**
     * 보존 기한({@code retainUntil})이 지난 스냅샷을 지운다. <b>최신 포인터가 가리키는 스냅샷은 건너뛴다</b> — 기간과 상관없이
     * 비교 기준이다(점검 C1). 포인터를 못 읽으면 "최신 없음"으로 보지 않고 정리 전체를 멈춘다.
     *
     * <p>후보마다 {@code GetItem} 으로 기한을 다시 읽지 않는다. GSI 가 {@code ProjectionType.ALL} 이라 그 값은 이미 손에 있다.
     */
    @Override
    public Mono<Integer> purgeExpired() {
        long now = clock.instant().getEpochSecond();
        return latestId()
                .defaultIfEmpty("")
                .flatMapMany(latest -> snapshotIndexItems()
                        .filter(item -> Attrs.longValue(item, RETAIN_UNTIL) <= now)
                        .map(item -> Keys.parseSnapshotPk(Attrs.str(item, Keys.PK)))
                        .filter(id -> !id.equals(latest)))
                .flatMap(id -> deleteSnapshot(id).thenReturn(1), DELETE_CONCURRENCY)
                .reduce(0, Integer::sum)
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("보존 기간이 지난 스냅샷 {}건을 정리했다", count);
                    }
                });
    }
```

`queryPartition` 의 `QueryRequest.builder()` 에 `.consistentRead(true)` 를 더한다(본 테이블 Query — 저장 직후 읽어도 튜플 수가 맞게).

import 에 `dev.starryeye.organization.core.port.SnapshotIntegrityException` 을 더한다. `Keys.EXPIRES_AT` 를 이 파일에서 더 쓰지 않으면 참조가 자바독에만 남는다(그대로 둔다).

- [ ] **Step 5: 저장소 테스트 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests dev.starryeye.organization.storage.DynamoDbTupleSnapshotRepositoryTest`
Expected: PASS (기존 `만료된_스냅샷만_정리된다`·`리셋하면_전부_사라진다` 포함).

- [ ] **Step 6: core 계약 테스트를 쓴다**

`FullSyncUseCaseTest` 에 import `dev.starryeye.organization.core.port.SnapshotIntegrityException` 을 더하고:

```java
    @Test
    @DisplayName("기준선 스냅샷이 깨져 있으면 아무것도 쓰지 않고 FAILED 로 끝나며, 이유에 복구 방법이 남는다")
    void 기준선이_깨지면_쓰지_않고_FAILED() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 메타가 없습니다 — POST /admin/sync/rebuild?mode=store 로 복구하세요"));

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("mode=store");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.saved).isEmpty();
        assertThat(state.users).isEmpty();
    }
```

`RebuildUseCaseTest` 에 import `dev.starryeye.organization.core.port.SnapshotIntegrityException`, `dev.starryeye.organization.core.model.SyncStatus`(이미 있으면 생략)를 더하고:

```java
    @Test
    @DisplayName("snapshot 모드는 기준선 스냅샷이 깨져 있으면 아무것도 지우거나 쓰지 않고 FAILED 로 끝난다")
    void snapshot_모드는_기준선이_깨지면_멈춘다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 메타가 없습니다 — POST /admin/sync/rebuild?mode=store 로 복구하세요"));

        // when
        var run = useCase.execute(RebuildMode.SNAPSHOT).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("mode=store");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.resetCount).hasValue(0);
        assertThat(source.fetchCount).hasValue(0);
    }
```

- [ ] **Step 7: core 테스트를 돌리고 변이로 확인한다**

Run: `./gradlew :core:test --tests dev.starryeye.organization.core.usecase.FullSyncUseCaseTest --tests dev.starryeye.organization.core.usecase.RebuildUseCaseTest`
Expected: PASS — core 는 이미 오류를 FAILED 로 바꾼다. 이 두 테스트는 **계약 고정**이다. 변이로 확인한다:
`FullSyncUseCase.baseline()` 에 잠깐 `.onErrorResume(e -> Mono.just(Set.of()))` 를 붙이면 `기준선이_깨지면_쓰지_않고_FAILED` 가 FAIL 이어야 한다.
확인 뒤 되돌린다(`git diff core/src/main` 이 비어야 한다). 보고서에 변이 결과를 한 줄 적는다.

- [ ] **Step 8: README**

"## 스냅샷이 왜 있는가" 절 끝(`실패가 나면 둘이 갈린다.` 문단 뒤)에 문단을 더한다:

```markdown
**최신 스냅샷은 기간과 상관없이 남는다.** 스냅샷은 `dynamodb.snapshot-retention-days`(기본 7일) 동안 보관하고, 매일 정리 작업이
지난 것을 지운다 — 다만 **최신 포인터가 가리키는 스냅샷은 건너뛴다.** 새 스냅샷은 변경이 있고 끝까지 간 회차만 만들므로, 삭제 가드
중단이나 LDAP 장애가 보존 기간보다 길게 이어져도 비교 기준이 사라지지 않는다. 스냅샷에는 테이블 TTL 을 쓰지 않는다.
기준선을 온전히 읽지 못하면(포인터가 가리키는 스냅샷의 메타가 없거나 튜플 수가 다르면) 빈 기준선으로 넘어가지 않고 그 회차를
FAILED 로 끝낸다 — `POST /admin/sync/rebuild?mode=store` 로 복구한다.
```

113행 근처 문단의 "페이지 책갈피뿐 아니라 이미 `expiresAt` 을 갖고 있던 튜플 스냅샷·동기화 실행 이력·쓰기 락 아이템도 함께
만료시킨다." 를 "페이지 책갈피뿐 아니라 동기화 실행 이력·쓰기 락 아이템도 함께 만료시킨다(튜플 스냅샷은 TTL 을 쓰지 않는다 — 위 \"스냅샷이 왜 있는가\")."
로 바꾼다.

- [ ] **Step 9: 모듈 테스트 전체와 커밋**

Run: `./gradlew :storage-dynamodb:test` 그다음 `./gradlew :core:test` (차례로)
Expected: PASS

```bash
git add core/src/main/java/dev/starryeye/organization/core/port core/src/testFixtures storage-dynamodb/src core/src/test README.md
git commit -m "fix: 최신 튜플 스냅샷은 보존 기간이 지나도 지우지 않는다 — TTL 대신 정리 작업이 최신을 건너뜀, 메타 먼저, 기준선이 깨지면 FAILED"
git push
```

---

### Task 2: C4 — 멤버 빼기 필터를 표준 해석기로 읽는다

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java:34-36, 85-92`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java`
- Modify: `README.md` (SCIM PATCH 표의 `members[value eq "..."]` 행)

**Interfaces:**
- Consumes: `ScimFilter.parse(String raw, ScimResourceType type)` → `ScimFilter(List<Term> terms)`, `Term(String attribute /*소문자*/, Object value /*String|Boolean*/)`.
  문법 오류는 `ScimException.invalidFilter`(400). `ScimResourceType.GROUP`.
- Produces: 없음(내부 변경).

- [ ] **Step 1: 실패 테스트를 쓴다**

`ScimPatchApplierTest` 의 `작은따옴표_필터도_인식한다` 를 지우고 아래로 바꾼다. 나머지 테스트를 더한다
(import `org.springframework.http.HttpStatus` 는 이미 있다):

```java
    @Test
    @DisplayName("작은따옴표로 감싼 필터 값은 400 invalidFilter 다 — RFC 7644 는 큰따옴표 JSON 문자열만 정한다")
    void 작은따옴표_필터는_거절한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when, then
        assertThatThrownBy(() -> 적용한다(before, 패치("remove", "members[value eq 'kim']", null), USER_ONLY))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("invalidFilter");
                });
    }

    @Test
    @DisplayName("아이디에 작은따옴표가 든 멤버(o'brien)도 필터 remove 로 빠진다")
    void 작은따옴표가_든_아이디를_뺀다() {
        // given — 아이디는 userName 에서 오고 IdNormalizer 는 ' 를 남긴다
        var before = 조직(MemberRef.user("o'brien@corp.com"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, 패치("remove", "members[value eq \"o'brien@corp.com\"]", null), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("필터 값의 JSON 이스케이프를 푼다 — \\\" 는 큰따옴표 한 글자다")
    void 이스케이프를_푼다() {
        // given
        var before = 조직(MemberRef.user("a\"b"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, 패치("remove", "members[value eq \"a\\\"b\"]", null), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("속성 이름의 대소문자는 가리지 않고, 값 안의 ] 도 값이다")
    void 대소문자와_값_안의_괄호() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("x]y"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("remove", "Members[Value eq \"kim\"]", null),
                new ScimOperation("remove", "members[value eq \"x]y\"]", null))), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("value eq \"…\" 한 항이 아니거나 값이 비면 400 invalidPath 다 — 500 으로 새지 않는다")
    void 한_항이_아니면_거절한다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when, then
        for (String path : List.of(
                "members[value eq \"kim\" and value eq \"lee\"]",
                "members[display eq \"kim\"]",
                "members[value eq true]",
                "members[value eq \"\"]",
                "members[value eq \"   \"]")) {
            assertThatThrownBy(() -> 적용한다(before, 패치("remove", path, null), USER_ONLY))
                    .as(path)
                    .isInstanceOfSatisfying(ScimException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getScimType()).isEqualTo("invalidPath");
                    });
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests dev.starryeye.organization.scim.ScimPatchApplierTest`
Expected: FAIL — `작은따옴표_필터는_거절한다`(지금은 kim 을 뺌), `작은따옴표가_든_아이디를_뺀다`·`이스케이프를_푼다`(invalidPath).
`대소문자와_값_안의_괄호`·`한_항이_아니면_거절한다` 는 지금도 통과한다(정규식이 둘 다 처리한다) — 새 구현이 이 동작을 잃거나 빈 값에서 500 으로
새지 않게 지키는 테스트다. 기존 `필터_remove의_value도_정규화된다`·`필터_remove는_종류를_구분한다` 는 통과.

- [ ] **Step 3: 구현한다**

`ScimPatchApplier` 의 `MEMBER_VALUE_FILTER` 를 바꾼다:

```java
    /**
     * {@code members[...]}. 대괄호 안은 목록 조회와 같은 {@link ScimFilter} 가 읽는다 — RFC 8259 JSON 문자열(큰따옴표만 구분자, 이스케이프 풀기).
     * 정규식으로 따옴표를 흉내 내면 값 안의 작은따옴표({@code o'brien})에서 끊긴다(점검 C4).
     */
    private static final Pattern MEMBER_FILTER = Pattern.compile("^members\\[(?<filter>.*)]$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
```

`applyOne` 의 필터 분기를 바꾼다:

```java
        Matcher filter = MEMBER_FILTER.matcher(path.trim());
        if (filter.matches()) {
            if (!op.equals("remove")) {
                throw ScimException.invalidPath(
                        "members 필터는 remove 에만 지원합니다: op=" + operation.op() + ", path=" + path);
            }
            return Mono.just(change.removingId(memberId(filter.group("filter"), path)));
        }
```

도우미를 더한다(그룹 절 안, `applyOne` 아래):

```java
    /**
     * {@code members[value eq "<아이디>"]} 의 아이디. 한 항 {@code value eq "…"} 말고는 받지 않는다 — 문법 오류는 {@link ScimFilter} 가
     * {@code invalidFilter} 로, 모양이 다르면 여기서 {@code invalidPath} 로 거절한다. 빈 값은 아이디가 될 수 없어 {@code invalidPath} 다
     * ({@link IdNormalizer} 의 예외가 500 으로 새지 않게).
     */
    private static String memberId(String filter, String path) {
        ScimFilter parsed = ScimFilter.parse(filter, ScimResourceType.GROUP);
        if (parsed.terms().size() != 1) {
            throw ScimException.invalidPath("members 필터는 value eq \"<아이디>\" 한 항만 지원합니다: " + path);
        }
        ScimFilter.Term term = parsed.terms().get(0);
        if (!"value".equals(term.attribute()) || !(term.value() instanceof String value) || value.isBlank()) {
            throw ScimException.invalidPath("members 필터는 value eq \"<아이디>\" 한 항만 지원합니다: " + path);
        }
        return IdNormalizer.normalize(value);
    }
```

`ScimFilter` 와 `ScimResourceType` 은 같은 패키지다(import 불필요).

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: PASS (모듈 전체 — 핸들러·E2E 성격 테스트가 `members[value eq \"…\"]` 를 쓴다).

- [ ] **Step 5: README 와 커밋**

SCIM PATCH 표의 행 `| Group | \`members[value eq "..."]\` | \`remove\` |` 를
`| Group | \`members[value eq "..."]\` | \`remove\` — 값은 큰따옴표 JSON 문자열(이스케이프 풀림). 작은따옴표로 감싸면 400 \`invalidFilter\` |` 로 바꾼다.

```bash
git add connector-scim/src README.md
git commit -m "fix: 멤버 빼기 필터를 표준 해석기로 읽는다 — 작은따옴표가 든 아이디도 빠지고, 작은따옴표로 감싼 값은 400"
git push
```

---

### Task 3: C8 — 우리가 부르는 Check 는 모두 캐시를 우회한다

**Files:**
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java:42-52, 97-103`
- Create: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaCheckCacheTest.java`
- Modify: `README.md` (§ "요구 버전")

**Interfaces:**
- Consumes: SDK 0.9.11 — `OpenFgaClient#check(ClientCheckRequest, ClientCheckOptions)`, `OpenFgaClient#batchCheck(ClientBatchCheckRequest, ClientBatchCheckOptions)`,
  `ClientCheckOptions#consistency(ConsistencyPreference)`, `ClientBatchCheckOptions#consistency(ConsistencyPreference)`,
  `dev.openfga.sdk.api.model.ConsistencyPreference.HIGHER_CONSISTENCY`(패키지 `dev.openfga.sdk.api.configuration` 의 옵션 클래스).
- Produces: 없음.

- [ ] **Step 1: 캐시를 켠 OpenFGA 로 실패 테스트를 쓴다**

`authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaCheckCacheTest.java`:

```java
package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenFGA Check 캐시를 켠 채로 쓰기 경로의 기준선을 본다(점검 C8). 캐시는 기본 꺼짐이라 다른 테스트는 이 문제를 드러낼 수 없다.
 * "없음"을 한 번 물어 캐시에 남긴 뒤 튜플을 쓰고 곧바로 다시 물었을 때 "있음"이어야 한다 — 캐시된 "없음"을 받으면 다음 연산이
 * 이미 쓴 튜플을 없다고 보고 지우지 않는다.
 */
@Testcontainers
class OpenFgaCheckCacheTest {

    @Container
    static final GenericContainer<?> CACHED_OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withEnv("OPENFGA_CHECK_QUERY_CACHE_ENABLED", "true")
            .withEnv("OPENFGA_CHECK_QUERY_CACHE_TTL", "60s")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    private StoreBootstrapper bootstrapper;
    private OpenFgaRelationTupleWriter writer;
    private OpenFgaRelationTupleChecker checker;

    @BeforeEach
    void 캐시를_켠_OpenFGA를_준비한다() {
        OpenFgaProperties properties = new OpenFgaProperties();
        properties.setApiUrl("http://" + CACHED_OPENFGA.getHost() + ":" + CACHED_OPENFGA.getMappedPort(8080));
        properties.setStoreName("cache-test-" + UUID.randomUUID());
        properties.setWriteBatchSize(100);
        properties.setMaxRetries(3);
        bootstrapper = new StoreBootstrapper(properties);
        bootstrapper.resolveStore().block();
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
        checker = new OpenFgaRelationTupleChecker(bootstrapper);
    }

    @Test
    @DisplayName("BatchCheck 기준선은 캐시를 우회한다 — 없음을 물은 뒤 쓴 튜플을 곧바로 다시 물으면 있음이다")
    void BatchCheck는_캐시를_우회한다() {
        // given — 없는 튜플을 물어 캐시에 "없음"을 남긴다
        var 튜플 = RelationTuple.directMember("kim", "DEV002");
        assertThat(checker.existing(Set.of(튜플)).block()).isEmpty();

        // when — 튜플을 쓰고 곧바로 다시 묻는다
        writer.apply(TupleDelta.writeOnly(Set.of(튜플))).block();
        var 다시 = checker.existing(Set.of(튜플)).block();

        // then
        assertThat(다시).containsExactly(튜플);
    }

    @Test
    @DisplayName("단건 Check 도 캐시를 우회한다 — 관리 조회의 '실제' 칸이 거짓 어긋남을 보이지 않는다")
    void Check도_캐시를_우회한다() {
        // given
        var 물음 = new RelationTuple("user:lee", "member", "group:DEV002");
        assertThat(checker.check(물음).block()).isFalse();

        // when
        writer.apply(TupleDelta.writeOnly(Set.of(RelationTuple.directMember("lee", "DEV002")))).block();
        var 다시 = checker.check(물음).block();

        // then
        assertThat(다시).isTrue();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :authz-openfga:test --tests dev.starryeye.organization.authz.OpenFgaCheckCacheTest`
Expected: 두 테스트 FAIL(캐시된 "없음"/`false`). **통과하면 멈추고 보고한다** — 캐시 설정이 먹지 않은 것이다(환경 변수 이름, 컨테이너 로그의
`check query cache` 설정 줄을 확인해 보고서에 적는다). 캐시를 실제로 켜 RED 를 본 뒤에만 다음 단계로 간다.

- [ ] **Step 3: 구현한다**

`OpenFgaRelationTupleChecker` 에 import 를 더한다:
`dev.openfga.sdk.api.configuration.ClientBatchCheckOptions`, `dev.openfga.sdk.api.configuration.ClientCheckOptions`, `dev.openfga.sdk.api.model.ConsistencyPreference`.

클래스 안에 상수를 둔다:

```java
    /**
     * 캐시가 아니라 지금 실제로 있는 것을 묻는다(점검 C8). 쓰기 경로는 Check 결과를 "지금 있는 것"으로 보고 무엇을 쓰고 지울지 정한다 —
     * OpenFGA Check 캐시를 켜면 기본(MINIMIZE_LATENCY)은 캐시된 답을 줘, 넣고 곧바로 뺀 멤버의 튜플이 안 지워진다. 관리 조회·아카이빙도
     * "실제"를 보여 주는 자리라 같은 규칙을 쓴다. 권한을 묻는 다른 앱은 계속 캐시를 쓸 수 있다.
     */
    private static final ConsistencyPreference 실제_값 = ConsistencyPreference.HIGHER_CONSISTENCY;
```

`check` 의 호출을 바꾼다:

```java
                                return bootstrapper.clientFor(storeId).check(new ClientCheckRequest()
                                                .user(tuple.user())
                                                .relation(tuple.relation())
                                                ._object(tuple.object()),
                                        new ClientCheckOptions().consistency(실제_값));
```

`checkChunk` 의 호출을 바꾼다:

```java
                        return bootstrapper.clientFor(storeId)
                                .batchCheck(new ClientBatchCheckRequest().checks(items),
                                        new ClientBatchCheckOptions().consistency(실제_값));
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :authz-openfga:test`
Expected: PASS (모듈 전체).

- [ ] **Step 5: README 와 커밋**

"## 요구 버전" 절의 OpenFGA 문단 뒤에 한 문단을 더한다:

```markdown
**OpenFGA Check 캐시를 켜도 된다.** 이 서버가 부르는 Check·BatchCheck 는 `HIGHER_CONSISTENCY` 로 캐시를 우회한다 — 쓰기 전 기준선이
캐시된 답이면 넣고 곧바로 뺀 멤버의 튜플이 안 지워지기 때문이다. 권한을 묻는 다른 앱의 Check 는 캐시를 그대로 쓴다.
(`HIGHER_CONSISTENCY` 는 OpenFGA v1.5.7 부터 있다 — 위 v1.10.0 요구에 포함된다.)
```

```bash
git add authz-openfga/src README.md
git commit -m "fix: 이 서버의 Check·BatchCheck 는 OpenFGA 캐시를 우회한다 — HIGHER_CONSISTENCY"
git push
```

---

### Task 4: C5 — LDAP 연결·읽기 타임아웃

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapProperties.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapConfig.java:18-26`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapTimeoutTest.java`
- Modify: `app-ldap/src/main/resources/application.yml` (`ldap:` 블록)
- Modify: `README.md` (§ "## LDAP")

**Interfaces:**
- Produces: `LdapProperties#getConnectTimeout()`/`getReadTimeout()` (`java.time.Duration`, 기본 10초·150초), 설정 키 `ldap.connect-timeout`·`ldap.read-timeout`.
- Produces: `LdapConfig.jndiTimeouts(LdapProperties)` — 패키지 전용 static, `Map<String, Object>`(값은 밀리초 문자열).

- [ ] **Step 1: 실패 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapTimeoutTest.java`:

```java
package dev.starryeye.organization.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LDAP 연결·읽기 타임아웃(점검 C5). 타임아웃이 없으면 JNDI 는 응답이 올 때까지 기다린다 — 죽은 연결 하나에 회차가 끝나지 않아
 * 실행 가드가 안 풀리고 이후 매일 동기화가 건너뛰어진다.
 */
class LdapTimeoutTest {

    @Test
    @DisplayName("기본 타임아웃은 연결 10초·읽기 150초다")
    void 기본값() {
        // when
        var timeouts = LdapConfig.jndiTimeouts(new LdapProperties());

        // then
        assertThat(timeouts)
                .containsEntry("com.sun.jndi.ldap.connect.timeout", "10000")
                .containsEntry("com.sun.jndi.ldap.read.timeout", "150000");
    }

    @Test
    @DisplayName("설정한 타임아웃이 JNDI 환경 값(밀리초)으로 실린다")
    void 설정값이_실린다() {
        // given
        var properties = new LdapProperties();
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(7));

        // when
        var timeouts = LdapConfig.jndiTimeouts(properties);

        // then
        assertThat(timeouts)
                .containsEntry("com.sun.jndi.ldap.connect.timeout", "3000")
                .containsEntry("com.sun.jndi.ldap.read.timeout", "7000");
    }

    @Test
    @DisplayName("접속만 받고 응답하지 않는 서버에서는 읽기 타임아웃으로 실패하고, 일시 장애로 보고 다시 읽는다")
    void 응답_없는_서버는_타임아웃으로_끝난다() throws IOException {
        try (ServerSocket 침묵하는_서버 = new ServerSocket(0)) {
            // given — 접속은 받아 두기만 하고 아무것도 쓰지 않는다
            List<Socket> 받은_접속 = new CopyOnWriteArrayList<>();
            Thread 받기 = new Thread(() -> {
                try {
                    while (true) {
                        받은_접속.add(침묵하는_서버.accept());
                    }
                } catch (IOException ignored) {
                    // 테스트가 끝나 서버 소켓이 닫혔다
                }
            });
            받기.setDaemon(true);
            받기.start();

            LdapProperties properties = new LdapProperties();
            properties.setUrl("ldap://localhost:" + 침묵하는_서버.getLocalPort());
            properties.setBindDn("cn=admin,dc=example,dc=com");
            properties.setBindPassword("password");
            properties.setReadTimeout(Duration.ofSeconds(1));
            properties.setMaxRetries(1);

            LdapConfig config = new LdapConfig();
            LdapContextSource contextSource = config.ldapContextSource(properties);
            contextSource.afterPropertiesSet();
            var source = config.ldapDirectorySnapshotSource(
                    config.ldapTemplate(contextSource),
                    config.ldapMappingStrategy(properties, Clock.systemUTC()),
                    properties);

            // when, then — 타임아웃이 없으면 20초 뒤 block 이 먼저 포기한다(그 예외에는 read timed out 이 없다)
            assertThatThrownBy(() -> source.fetchAll().block(Duration.ofSeconds(20)))
                    .hasStackTraceContaining("read timed out");
            assertThat(받은_접속).as("최초 1회 + 재시도 1회 — 시도마다 연결 하나").hasSize(2);

            for (Socket socket : 받은_접속) {
                socket.close();
            }
        }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests dev.starryeye.organization.ldap.LdapTimeoutTest`
Expected: 컴파일 실패(`jndiTimeouts`·`setConnectTimeout`·`setReadTimeout` 없음). 먼저 Step 3 의 `LdapProperties` 필드와 `jndiTimeouts` 를
**빈 Map 을 돌려주는 자리표시**로 넣어 컴파일만 되게 한 뒤 다시 돌린다 — Expected: `기본값`·`설정값이_실린다` FAIL(빈 맵),
`응답_없는_서버는_타임아웃으로_끝난다` FAIL(20초 뒤 `Timeout on blocking read`, `read timed out` 없음). 그다음 Step 3 을 끝까지 한다.

- [ ] **Step 3: 구현한다**

`LdapProperties` 에 필드를 더한다(`maxRetries` 아래, import `java.time.Duration`):

```java
    /** 접속 타임아웃. 이보다 오래 걸리면 실패한다(JNDI {@code com.sun.jndi.ldap.connect.timeout}). */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 응답 타임아웃. 요청 뒤 응답을 이보다 오래 못 받으면 실패한다(JNDI {@code com.sun.jndi.ldap.read.timeout}).
     *
     * <p>없으면 JNDI 는 응답이 올 때까지 기다린다 — 조용히 죽은 연결 하나에 회차가 끝나지 않아 실행 가드가 안 풀리고, 이후 매일 동기화가
     * 건너뛰어진다(점검 C5). AD 는 검색 하나를 최대 120초({@code MaxQueryDuration})까지 허용하므로, 서버가 정상적으로 오래 일하는 경우를
     * 먼저 끊지 않게 그보다 길게 둔다. 타임아웃은 일시 장애로 분류돼 {@link #maxRetries} 만큼 처음부터 다시 읽는다.
     */
    private Duration readTimeout = Duration.ofSeconds(150);
```

`LdapConfig` — `ldapContextSource` 에서 `setPassword` 다음 줄에 넣고, 메서드를 더한다(import `java.util.Map`):

```java
        contextSource.setBaseEnvironmentProperties(jndiTimeouts(properties));
```

```java
    /**
     * JNDI LDAP 타임아웃(밀리초 문자열). 페이징·범위 검색용 {@code SingleContextSource}({@link LdapTemplates#한_커넥션에서})도
     * 이 컨텍스트 소스에서 커넥션을 얻으므로 같은 값을 탄다.
     */
    static Map<String, Object> jndiTimeouts(LdapProperties properties) {
        return Map.of(
                "com.sun.jndi.ldap.connect.timeout", String.valueOf(properties.getConnectTimeout().toMillis()),
                "com.sun.jndi.ldap.read.timeout", String.valueOf(properties.getReadTimeout().toMillis()));
    }
```

`app-ldap/src/main/resources/application.yml` 의 `ldap:` 블록 `page-size: 500` 아래에 더한다:

```yaml
  connect-timeout: 10s
  read-timeout: 150s
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-ldap:test`
Expected: PASS (모듈 전체 — 임베디드 LDAP 테스트들이 새 타임아웃 아래에서도 통과해야 한다). `응답_없는_서버는_타임아웃으로_끝난다` 는 약 2~3초.

- [ ] **Step 5: README 와 커밋**

"## LDAP" 절의 "**다시 읽어도 결과가 같은 실패는 재시도하지 않는다.**" 문단 뒤에 더한다:

```markdown
**연결·응답에 타임아웃이 있다.** `ldap.connect-timeout`(기본 10초)과 `ldap.read-timeout`(기본 150초)이다. 응답이 오지 않는 연결에
물리면 읽기 타임아웃으로 실패하고, 일시 장애로 보고 `ldap.max-retries` 만큼 처음부터 다시 읽는다 — 그래도 안 되면 그 회차는 FAILED 이고
다음 회차는 정상으로 돈다. 읽기 기본값은 AD 가 검색 하나에 허용하는 최대 시간(120초, `MaxQueryDuration`)보다 길게 잡았다. 한 페이지
응답이 이보다 오래 걸리는 디렉터리라면 늘린다.
```

```bash
git add connector-ldap/src app-ldap/src/main/resources/application.yml README.md
git commit -m "fix: LDAP 연결·읽기 타임아웃 — 죽은 연결 하나에 동기화가 영원히 멈추지 않는다"
git push
```

---

## 컨트롤러 마무리 (과제 아님)

- 전체 `./gradlew cleanTest test` → `./gradlew cleanScaleTest scaleTest` (한 번에 하나). 결과를 스펙 §8 에 적는다.
- 점검 문서 `docs/superpowers/specs/2026-09-28-full-audit.md` §1 요약표의 C1·C4·C5·C8 한 줄 설명 끝에 " **→ 해결(2026-09-29, 슬라이드 ①)**" 을 붙인다.
- 같은 커밋으로 푸시한다.

package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
@Slf4j
@RequiredArgsConstructor
public class DynamoDbTupleSnapshotRepository implements TupleSnapshotRepository {

    private static final int BATCH_SIZE = 25;
    private static final int DELETE_CONCURRENCY = 4;

    /** BatchWriteItem 의 UnprocessedItems 재시도 상한. AWS 는 지수 백오프 재시도를 권장한다. */
    private static final int MAX_BATCH_ATTEMPTS = 5;
    private static final Duration BATCH_RETRY_BASE_DELAY = Duration.ofMillis(100);

    private static final String CREATED_AT = "createdAt";
    private static final String SOURCE = "source";
    private static final String TUPLE_COUNT = "tupleCount";
    private static final String SNAPSHOT_ID = "snapshotId";
    private static final String RETAIN_UNTIL = "retainUntil";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        return doSave(snapshot, clock.instant());
    }

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

    private Mono<Void> writeMeta(TupleSnapshot snapshot, long retainUntil) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshot.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.SNAPSHOT_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(Keys.sortableTimestamp(snapshot.createdAt())));
        item.put(CREATED_AT, Attrs.s(snapshot.createdAt().toString()));
        item.put(SOURCE, Attrs.s(snapshot.source().name()));
        item.put(TUPLE_COUNT, Attrs.n(snapshot.tuples().size()));
        item.put(RETAIN_UNTIL, Attrs.n(retainUntil));
        return putItem(item);
    }

    private Mono<Void> writePointer(String snapshotId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER));
        item.put(Keys.SK, Attrs.s(Keys.LATEST));
        item.put(SNAPSHOT_ID, Attrs.s(snapshotId));
        return putItem(item);
    }

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

    @Override
    public Mono<TupleSnapshot> findById(String snapshotId) {
        return queryPartition(Keys.snapshotPk(snapshotId))
                .collectList()
                .flatMap(items -> Mono.justOrEmpty(toSnapshot(snapshotId, items)));
    }

    private TupleSnapshot toSnapshot(String snapshotId, List<Map<String, AttributeValue>> items) {
        Map<String, AttributeValue> meta = items.stream()
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .findFirst()
                .orElse(null);
        if (meta == null) {
            return null;
        }
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (Map<String, AttributeValue> item : items) {
            String sk = Attrs.str(item, Keys.SK);
            if (Keys.isTupleSk(sk)) {
                tuples.add(Keys.parseTupleSk(sk));
            }
        }
        int expected = Attrs.integer(meta, TUPLE_COUNT);
        if (tuples.size() != expected) {
            throw new SnapshotIntegrityException(
                    "스냅샷 %s 를 온전히 읽지 못했습니다(메타 튜플 %d · 읽음 %d) — POST /admin/sync/rebuild?mode=store 로 복구하세요"
                            .formatted(snapshotId, expected, tuples.size()));
        }
        return new TupleSnapshot(
                snapshotId,
                Attrs.instant(meta, CREATED_AT),
                SyncSource.valueOf(Attrs.str(meta, SOURCE)),
                tuples);
    }

    @Override
    public Flux<SnapshotMeta> listRecent(int days) {
        Instant from = clock.instant().minus(Duration.ofDays(days));
        return snapshotMetas()
                .filter(meta -> !meta.createdAt().isBefore(from));
    }

    /**
     * GSI1 SNAPSHOT_INDEX 파티션을 createdAt 역순으로 훑어 <b>원본 아이템</b>을 돌려준다.
     * 이 인덱스는 {@code ProjectionType.ALL} 이라 {@code retainUntil} 을 포함한 모든 속성이
     * 이미 실려 온다 — 그것을 쓰는 곳은 다시 읽지 않아도 된다.
     */
    private Flux<Map<String, AttributeValue>> snapshotIndexItems() {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.SNAPSHOT_INDEX)))
                .scanIndexForward(false)
                .build();

        return Paginator.queryAll(client, request);
    }

    /** GSI1 SNAPSHOT_INDEX 파티션을 createdAt 역순으로 훑는다. */
    private Flux<SnapshotMeta> snapshotMetas() {
        return snapshotIndexItems().map(DynamoDbTupleSnapshotRepository::toMeta);
    }

    private static SnapshotMeta toMeta(Map<String, AttributeValue> item) {
        return new SnapshotMeta(
                Keys.parseSnapshotPk(Attrs.str(item, Keys.PK)),
                Attrs.instant(item, CREATED_AT),
                SyncSource.valueOf(Attrs.str(item, SOURCE)),
                Attrs.integer(item, TUPLE_COUNT));
    }

    @Override
    public Mono<Void> reset() {
        return snapshotMetas()
                .flatMap(meta -> deleteSnapshot(meta.id()), DELETE_CONCURRENCY)
                .then(deleteItem(Keys.SNAPSHOT_POINTER, Keys.LATEST));
    }

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

    /**
     * 튜플 먼저, 메타 마지막 — 중간에 실패해도 메타가 남아 다음 정리가 다시 찾는다. 저장 순서(메타 → 튜플 → 포인터)를
     * 그대로 뒤집은 순서다 — 메타를 먼저 지우면 튜플 배치 도중 실패했을 때 그 조각을 아무도 다시 찾지 못한다.
     */
    private Mono<Void> deleteSnapshot(String snapshotId) {
        return queryPartition(Keys.snapshotPk(snapshotId))
                .filter(item -> !Keys.META.equals(Attrs.str(item, Keys.SK)))
                .map(item -> WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder()
                                .key(Map.of(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)),
                                        Keys.SK, Attrs.s(Attrs.str(item, Keys.SK))))
                                .build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(this::batchWrite)
                .then(Mono.defer(() -> deleteItem(Keys.snapshotPk(snapshotId), Keys.META)));
    }

    // ---------- 공통 ----------

    /**
     * UnprocessedItems 가 남으면 다시 보낸다. DynamoDB 는 배치 일부를 거절할 수 있고,
     * AWS 는 지수 백오프로 재시도할 것을 권장한다. 재시도 상한을 두지 않으면 지속적인
     * 스로틀링 아래에서 무한히 돌며 서비스에 핫루프를 거는 셈이라, {@link #MAX_BATCH_ATTEMPTS}
     * 를 넘기면 남은 건수를 담아 에러로 실패시킨다 — save() 가 실패하면 FullSyncUseCase 가
     * 이번 실행을 FAILED 로 기록하고 다음 동기화는 온전한 이전 스냅샷을 기준으로 다시 diff 한다.
     */
    private Mono<Void> batchWrite(List<WriteRequest> requests) {
        return batchWrite(requests, 1);
    }

    private Mono<Void> batchWrite(List<WriteRequest> requests, int attempt) {
        if (requests.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromFuture(() -> client.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(properties.getTableName(), requests))
                        .build()))
                .flatMap(response -> {
                    List<WriteRequest> unprocessed =
                            response.unprocessedItems().getOrDefault(properties.getTableName(), List.of());
                    if (unprocessed.isEmpty()) {
                        return Mono.empty();
                    }
                    if (attempt >= MAX_BATCH_ATTEMPTS) {
                        return Mono.error(new IllegalStateException(
                                "BatchWriteItem 이 %d회 재시도한 뒤에도 %d건을 처리하지 못했다"
                                        .formatted(attempt, unprocessed.size())));
                    }
                    Duration delay = BATCH_RETRY_BASE_DELAY.multipliedBy(1L << (attempt - 1));
                    return Mono.delay(delay).then(batchWrite(unprocessed, attempt + 1));
                })
                .then();
    }

    private Flux<Map<String, AttributeValue>> queryPartition(String pk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request);
    }

    private Mono<Void> putItem(Map<String, AttributeValue> item) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item)
                .build())).then();
    }

    private Mono<Void> deleteItem(String pk, String sk) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                .build())).then();
    }
}

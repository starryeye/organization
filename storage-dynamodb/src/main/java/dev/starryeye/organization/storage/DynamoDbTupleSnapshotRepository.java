package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
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
 * <p>저장 순서는 튜플 → 메타 → 포인터다. 포인터를 마지막에 갱신해야
 * 중간에 죽어도 다음 동기화가 직전 스냅샷을 정상적으로 읽는다.
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
    private static final String EXPIRES_AT = "expiresAt";
    private static final String SNAPSHOT_ID = "snapshotId";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        return doSave(snapshot, clock.instant());
    }

    /** 테스트에서 과거 시각의 스냅샷을 만들기 위한 변형. TTL 을 snapshot.createdAt 기준으로 잡는다. */
    public Mono<Void> saveWithCreatedAt(TupleSnapshot snapshot) {
        return doSave(snapshot, snapshot.createdAt());
    }

    private Mono<Void> doSave(TupleSnapshot snapshot, Instant ttlBase) {
        long expiresAt = ttlBase.plus(Duration.ofDays(properties.getSnapshotRetentionDays())).getEpochSecond();

        return writeTuples(snapshot, expiresAt)
                .then(writeMeta(snapshot, expiresAt))
                .then(writePointer(snapshot.id()));
    }

    private Mono<Void> writeTuples(TupleSnapshot snapshot, long expiresAt) {
        return Flux.fromIterable(snapshot.tuples())
                .map(tuple -> WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(tupleItem(snapshot.id(), tuple, expiresAt)).build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(this::batchWrite)
                .then();
    }

    private Map<String, AttributeValue> tupleItem(String snapshotId, RelationTuple tuple, long expiresAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)));
        item.put(Keys.SK, Attrs.s(Keys.tupleSk(tuple)));
        item.put(EXPIRES_AT, Attrs.n(expiresAt));
        return item;
    }

    private Mono<Void> writeMeta(TupleSnapshot snapshot, long expiresAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshot.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.SNAPSHOT_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(Keys.sortableTimestamp(snapshot.createdAt())));
        item.put(CREATED_AT, Attrs.s(snapshot.createdAt().toString()));
        item.put(SOURCE, Attrs.s(snapshot.source().name()));
        item.put(TUPLE_COUNT, Attrs.n(snapshot.tuples().size()));
        item.put(EXPIRES_AT, Attrs.n(expiresAt));
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
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> Attrs.str(response.item(), SNAPSHOT_ID))
                .flatMap(this::findById);
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

    /** GSI1 SNAPSHOT_INDEX 파티션을 createdAt 역순으로 훑는다. */
    private Flux<SnapshotMeta> snapshotMetas() {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.SNAPSHOT_INDEX)))
                .scanIndexForward(false)
                .build();

        return paginate(request).map(item -> new SnapshotMeta(
                Keys.parseSnapshotPk(Attrs.str(item, Keys.PK)),
                Attrs.instant(item, CREATED_AT),
                SyncSource.valueOf(Attrs.str(item, SOURCE)),
                Attrs.integer(item, TUPLE_COUNT)));
    }

    @Override
    public Mono<Void> reset() {
        return snapshotMetas()
                .flatMap(meta -> deleteSnapshot(meta.id()), DELETE_CONCURRENCY)
                .then(deleteItem(Keys.SNAPSHOT_POINTER, Keys.LATEST));
    }

    @Override
    public Mono<Integer> purgeExpired() {
        long now = clock.instant().getEpochSecond();
        return snapshotMetas()
                .filterWhen(meta -> isExpired(meta.id(), now))
                .flatMap(meta -> deleteSnapshot(meta.id()).thenReturn(1), DELETE_CONCURRENCY)
                .reduce(0, Integer::sum)
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("만료된 스냅샷 {}건을 정리했다", count);
                    }
                });
    }

    private Mono<Boolean> isExpired(String snapshotId, long nowEpochSecond) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)), Keys.SK, Attrs.s(Keys.META)))
                        .build()))
                .map(response -> Attrs.longValue(response.item(), EXPIRES_AT) <= nowEpochSecond);
    }

    private Mono<Void> deleteSnapshot(String snapshotId) {
        return queryPartition(Keys.snapshotPk(snapshotId))
                .map(item -> WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder()
                                .key(Map.of(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)),
                                        Keys.SK, Attrs.s(Attrs.str(item, Keys.SK))))
                                .build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(this::batchWrite)
                .then();
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
                .build();
        return paginate(request);
    }

    /** LastEvaluatedKey 를 따라가며 전체 페이지를 이어붙인다. */
    private Flux<Map<String, AttributeValue>> paginate(QueryRequest request) {
        return Mono.fromFuture(() -> client.query(request))
                .flatMapMany(response -> {
                    Flux<Map<String, AttributeValue>> page = Flux.fromIterable(response.items());
                    if (response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()) {
                        return page;
                    }
                    return page.concatWith(paginate(
                            request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build()));
                });
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

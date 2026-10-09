package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenFGA 에 실제로 반영된 튜플의 기록.
 *
 * <p><b>본문은 압축 묶음이다</b>(설계 2026-10-09 §3). 튜플 목록을 gzip 으로 압축해 {@value #CHUNK_SIZE}바이트 이하 묶음 아이템 몇 개로
 * 나눈다({@link SnapshotChunks}). 튜플 한 줄을 아이템 하나로 쓰던 때는 10만 명(튜플 약 15만 줄)에 한 파티션으로 아이템 약 15만 개,
 * 쓰기 약 15만 WCU 가 몰렸다. 메타는 튜플 수({@code tupleCount})와 묶음 수({@code chunkCount})를 갖는다.
 *
 * <p>저장 순서는 메타 → 묶음 → 포인터다. 메타가 먼저라 묶음을 쓰다 죽어도 정리 작업이 그 조각을 찾아 지우고,
 * 포인터가 마지막이라 반쪽 스냅샷이 기준선이 되지 않는다.
 *
 * <p><b>스냅샷은 테이블 TTL({@link Keys#EXPIRES_AT})을 쓰지 않는다.</b> TTL 은 아이템마다 붙은 시각만 보고 지워 최신인지
 * 모른다 — 최신(비교 기준)까지 지워지면 다음 회차가 빈 기준선으로 돌아 삭제를 하나도 안 한다(점검 C1). 보관 기한은 메타의
 * {@code retainUntil} 에만 적고, {@link #purgeExpired()} 가 최신을 건너뛰며 지운다.
 */
@Slf4j
public class DynamoDbTupleSnapshotRepository implements TupleSnapshotRepository {

    /** 묶음 하나의 본문 상한(바이트). 아이템 한도(400KB)에서 키와 속성 이름이 들어갈 자리를 남긴다. */
    static final int CHUNK_SIZE = 350_000;

    private static final int BATCH_SIZE = BatchRequests.WRITE_LIMIT;
    private static final int DELETE_CONCURRENCY = 4;

    private static final String CREATED_AT = "createdAt";
    private static final String SOURCE = "source";
    private static final String TUPLE_COUNT = "tupleCount";
    private static final String CHUNK_COUNT = "chunkCount";
    private static final String DATA = "data";
    private static final String SNAPSHOT_ID = "snapshotId";
    private static final String RETAIN_UNTIL = "retainUntil";
    private static final String WRITING_SINCE = "writingSince";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;
    private final int chunkSize;

    public DynamoDbTupleSnapshotRepository(DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        this(client, properties, clock, CHUNK_SIZE);
    }

    /** 묶음 크기를 줄여 작은 스냅샷도 여러 묶음으로 만드는 테스트용. */
    DynamoDbTupleSnapshotRepository(DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock, int chunkSize) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.chunkSize = chunkSize;
    }

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

        return Mono.fromCallable(() -> SnapshotChunks.encode(snapshot.tuples(), chunkSize))
                .flatMap(chunks -> writeMeta(snapshot, retainUntil, chunks.size())
                        .then(writeChunks(snapshot.id(), chunks))
                        .then(writePointer(snapshot.id())));
    }

    /**
     * 묶음은 번호 순서로 하나씩 PutItem 으로 보낸다(설계 2026-10-09 §3.3). 묶음 하나가 최대 350 WCU 라, 여러 개를 BatchWriteItem 에 담으면
     * 한 파티션의 쓰기 한도(초당 1,000)에 걸려 일부만 처리되고 {@link BatchRequests} 의 재시도 예산(약 1.5초)이 모자랄 수 있다. 하나씩이면
     * 스로틀이 요청 전체의 오류로 와 SDK 재시도(이 클라이언트의 DynamoDB 기본값, 최대 9번·지수 백오프)가 받는다.
     */
    private Mono<Void> writeChunks(String snapshotId, List<byte[]> chunks) {
        return Flux.range(0, chunks.size())
                .concatMap(index -> putItem(chunkItem(snapshotId, index, chunks.get(index))))
                .then();
    }

    private static Map<String, AttributeValue> chunkItem(String snapshotId, int index, byte[] data) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)));
        item.put(Keys.SK, Attrs.s(Keys.chunkSk(index)));
        item.put(DATA, Attrs.b(data));
        return item;
    }

    private Mono<Void> writeMeta(TupleSnapshot snapshot, long retainUntil, int chunkCount) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshot.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.SNAPSHOT_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(Keys.sortableTimestamp(snapshot.createdAt())));
        item.put(CREATED_AT, Attrs.s(snapshot.createdAt().toString()));
        item.put(SOURCE, Attrs.s(snapshot.source().name()));
        item.put(TUPLE_COUNT, Attrs.n(snapshot.tuples().size()));
        item.put(CHUNK_COUNT, Attrs.n(chunkCount));
        item.put(RETAIN_UNTIL, Attrs.n(retainUntil));
        return putItem(item);
    }

    /** 줄을 통째로 새로 써 "기록 중" 칸도 지운다(설계 2026-09-30 §4.1). */
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
                        "기준선 스냅샷 %s 의 메타가 없습니다 — POST /admin/sync/rebuild 로 복구하세요".formatted(id)))));
    }

    /** 최신 포인터가 가리키는 스냅샷 id. 강한 일관성으로 읽는다 — 정리 작업이 이 값으로 최신을 건너뛴다. 포인터가 없거나 "기록 중" 표시만 있으면 빈 Mono. */
    private Mono<String> latestId() {
        return 포인터().flatMap(item -> Mono.justOrEmpty(Attrs.str(item, SNAPSHOT_ID)));
    }

    private Mono<Map<String, AttributeValue>> 포인터() {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .consistentRead(true)
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> response.item());
    }

    /** 포인터 줄에 칸 하나를 붙인다(UpdateItem) — 스냅샷 번호는 그대로 둔다. 포인터가 없으면 칸만 있는 줄이 생긴다. */
    @Override
    public Mono<Void> markWriting() {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .updateExpression("SET #writingSince = :now")
                        .expressionAttributeNames(Map.of("#writingSince", WRITING_SINCE))
                        .expressionAttributeValues(Map.of(":now", Attrs.s(clock.instant().toString())))
                        .build()))
                .then();
    }

    @Override
    public Mono<Boolean> isWriting() {
        return 포인터()
                .map(item -> item.containsKey(WRITING_SINCE))
                .defaultIfEmpty(false);
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
        Set<RelationTuple> tuples = new LinkedHashSet<>(본문(snapshotId, meta, items));
        int expected = Attrs.integer(meta, TUPLE_COUNT);
        if (tuples.size() != expected) {
            throw 온전하지_않다(snapshotId, "메타 튜플 %d · 읽음 %d".formatted(expected, tuples.size()));
        }
        return new TupleSnapshot(
                snapshotId,
                Attrs.instant(meta, CREATED_AT),
                SyncSource.valueOf(Attrs.str(meta, SOURCE)),
                tuples);
    }

    /**
     * 묶음을 번호 순서로 모아 푼다(설계 2026-10-09 §3.2). Query 는 정렬키 순서로 주고, 번호가 네 자리라 그 순서가 곧 번호 순서다.
     * 메타에 묶음 수가 없으면 옛 형식(튜플 한 줄이 아이템 하나)이다 — 운영 배포 전이라 이관하지 않고 재적재로 고친다.
     */
    private List<RelationTuple> 본문(String snapshotId, Map<String, AttributeValue> meta, List<Map<String, AttributeValue>> items) {
        if (!meta.containsKey(CHUNK_COUNT)) {
            throw 온전하지_않다(snapshotId, "메타에 묶음 수가 없다 — 옛 형식");
        }
        int expected = Attrs.integer(meta, CHUNK_COUNT);
        List<Map<String, AttributeValue>> chunks = items.stream()
                .filter(item -> Keys.isChunkSk(Attrs.str(item, Keys.SK)))
                .toList();
        if (chunks.size() != expected) {
            throw 온전하지_않다(snapshotId, "메타 묶음 %d · 읽음 %d".formatted(expected, chunks.size()));
        }
        List<byte[]> data = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            if (!Keys.chunkSk(index).equals(Attrs.str(chunks.get(index), Keys.SK))) {
                throw 온전하지_않다(snapshotId, "묶음 번호가 %d 에서 끊긴다".formatted(index));
            }
            data.add(Attrs.bytes(chunks.get(index), DATA));
        }
        try {
            return SnapshotChunks.decode(data);
        } catch (IOException e) {
            throw 온전하지_않다(snapshotId, "본문을 풀 수 없다: " + e);
        }
    }

    private static SnapshotIntegrityException 온전하지_않다(String snapshotId, String 사정) {
        return new SnapshotIntegrityException(
                "스냅샷 %s 를 온전히 읽지 못했습니다(%s) — POST /admin/sync/rebuild 로 복구하세요".formatted(snapshotId, 사정));
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
     * 메타가 아닌 줄(묶음, 옛 형식의 튜플 줄) 먼저, 메타 마지막 — 중간에 실패해도 메타가 남아 다음 정리가 다시 찾는다. 저장 순서
     * (메타 → 묶음 → 포인터)를 그대로 뒤집은 순서다 — 메타를 먼저 지우면 지우는 도중 실패했을 때 그 조각을 아무도 다시 찾지 못한다.
     *
     * <p>묶음은 쓸 때와 같은 까닭으로 번호 순서로 하나씩 DeleteItem 으로 지운다(설계 2026-10-09 §3.3, §8). DeleteItem 도 지우는 아이템의
     * 크기만큼 쓰기 용량을 써서(묶음 하나가 최대 약 350 WCU) 묶음 열몇 개를 BatchWriteItem 에 담으면 한 파티션의 쓰기 한도(초당 1,000)에
     * 걸려 {@link BatchRequests} 의 재시도 예산이 모자라고, 그 실패가 {@link #purgeExpired()} 의 다른 스냅샷 정리까지 끊는다. 하나씩이면
     * 스로틀이 요청 전체의 오류로 와 SDK 재시도가 받는다. 끝내 실패해도 메타가 남으므로 다음 정리가 남은 것을 이어 지운다.
     * 묶음이 아닌 줄(옛 형식의 튜플 줄)은 용량이 작아 지금처럼 묶음 요청(BatchWriteItem)으로 지운다.
     */
    private Mono<Void> deleteSnapshot(String snapshotId) {
        String pk = Keys.snapshotPk(snapshotId);
        return queryPartition(pk)
                .map(item -> Attrs.str(item, Keys.SK))
                .filter(sk -> !Keys.META.equals(sk))
                .collectList()
                .flatMap(sks -> deleteChunks(pk, sks).then(deleteOldFormatRows(pk, sks)))
                .then(Mono.defer(() -> deleteItem(pk, Keys.META)));
    }

    private Mono<Void> deleteChunks(String pk, List<String> sks) {
        return Flux.fromIterable(sks)
                .filter(Keys::isChunkSk)
                .concatMap(sk -> deleteItem(pk, sk))
                .then();
    }

    private Mono<Void> deleteOldFormatRows(String pk, List<String> sks) {
        BatchRequests 묶음 = new BatchRequests(client, properties.getTableName());
        return Flux.fromIterable(sks)
                .filter(sk -> !Keys.isChunkSk(sk))
                .map(sk -> WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder()
                                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                                .build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(묶음::write)
                .then();
    }

    // ---------- 공통 ----------

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

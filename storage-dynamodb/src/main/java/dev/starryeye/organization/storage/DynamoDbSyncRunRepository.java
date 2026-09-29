package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 동기화 1회의 실행 이력. 관측성의 실체이며, 특히 ABORTED 사유가 남아야
 * 사람이 강제 실행을 승인할지 판단할 수 있다.
 *
 * <p>SCIM push 요청은 여기 기록하지 않는다. 요청 단위 이력이 폭증하기 때문이다.
 */
@RequiredArgsConstructor
public class DynamoDbSyncRunRepository implements SyncRunRepository {

    private static final String RUN_ID = "runId";
    private static final String SOURCE = "source";
    private static final String TRIGGER = "trigger";
    private static final String STARTED_AT = "startedAt";
    private static final String FINISHED_AT = "finishedAt";
    private static final String STATUS = "status";
    private static final String WRITTEN_COUNT = "writtenCount";
    private static final String DELETED_COUNT = "deletedCount";
    private static final String FAILURE_COUNT = "failureCount";
    private static final String SNAPSHOT_ID = "snapshotId";
    private static final String MESSAGE = "message";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
        SyncRun run = SyncRun.started(UUID.randomUUID().toString(), source, trigger, clock.instant());
        return save(run).thenReturn(run);
    }

    @Override
    public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
        SyncRun finished = run.finished(outcome, clock.instant());
        return save(finished).thenReturn(finished);
    }

    private Mono<Void> save(SyncRun run) {
        long expiresAt = run.startedAt()
                .plus(Duration.ofDays(properties.getSyncrunRetentionDays()))
                .getEpochSecond();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.syncRunPk(run.startedAt())));
        item.put(Keys.SK, Attrs.s(Keys.syncRunSk(run.startedAt(), run.runId())));
        item.put(RUN_ID, Attrs.s(run.runId()));
        item.put(SOURCE, Attrs.s(run.source().name()));
        item.put(TRIGGER, Attrs.s(run.trigger().name()));
        item.put(STARTED_AT, Attrs.s(run.startedAt().toString()));
        item.put(STATUS, Attrs.s(run.status().name()));
        item.put(WRITTEN_COUNT, Attrs.n(run.writtenCount()));
        item.put(DELETED_COUNT, Attrs.n(run.deletedCount()));
        item.put(FAILURE_COUNT, Attrs.n(run.failureCount()));
        item.put(Keys.EXPIRES_AT, Attrs.n(expiresAt));
        if (run.finishedAt() != null) {
            item.put(FINISHED_AT, Attrs.s(run.finishedAt().toString()));
        }
        Attrs.putIfPresent(item, SNAPSHOT_ID, run.snapshotId());
        Attrs.putIfPresent(item, MESSAGE, run.message());

        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item)
                .build())).then();
    }

    /**
     * 이번 달 파티션을 최신순으로 읽고, 모자라면 지난달까지 이어 읽는다.
     * 파티션을 월 단위로 나눈 대가로 조회가 두 번 나뉜다.
     *
     * <p>이번 달 결과만으로 {@code limit} 이 채워지면 지난달 조회는 <b>실행되지 않는다</b> —
     * {@code concatWith} 는 앞이 끝나야 뒤를 구독하고, {@code take} 가 그 전에 끊기 때문이다.
     * {@code queryMonth} 자체는 요청 객체만 만들 뿐 구독 전에는 아무 일도 하지 않는다.
     */
    @Override
    public Flux<SyncRun> findRecent(int limit) {
        YearMonth thisMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        YearMonth lastMonth = thisMonth.minusMonths(1);

        return queryMonth(thisMonth)
                .concatWith(queryMonth(lastMonth))
                .take(limit);
    }

    /**
     * 이번 달과 지난달 파티션에서 번호로 찾는다. 기록은 보관 기간(기본 30일) 뒤 사라지므로 그보다 앞선 달에는 없다.
     *
     * <p>파티션 하나가 한 달치 기록(수십 건)이라 통째로 읽어도 싸다 — runId 로 찾는 인덱스를 따로 두지 않는다. 이번 달에서 찾으면
     * {@code next()} 가 구독을 끊어 지난달은 읽지 않는다({@link #findRecent} 와 같은 이유).
     */
    @Override
    public Mono<SyncRun> findById(String runId) {
        YearMonth thisMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        return queryMonth(thisMonth)
                .concatWith(queryMonth(thisMonth.minusMonths(1)))
                .filter(run -> runId.equals(run.runId()))
                .next();
    }

    private Flux<SyncRun> queryMonth(YearMonth month) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.syncRunPk(month))))
                .scanIndexForward(false)
                .build();

        return Paginator.queryAll(client, request).map(this::toRun);
    }

    private SyncRun toRun(Map<String, AttributeValue> item) {
        return SyncRun.builder()
                .runId(Attrs.str(item, RUN_ID))
                .source(SyncSource.valueOf(Attrs.str(item, SOURCE)))
                .trigger(SyncTrigger.valueOf(Attrs.str(item, TRIGGER)))
                .startedAt(Attrs.instant(item, STARTED_AT))
                .finishedAt(Attrs.instant(item, FINISHED_AT))
                .status(SyncStatus.valueOf(Attrs.str(item, STATUS)))
                .writtenCount(Attrs.integer(item, WRITTEN_COUNT))
                .deletedCount(Attrs.integer(item, DELETED_COUNT))
                .failureCount(Attrs.integer(item, FAILURE_COUNT))
                .snapshotId(Attrs.str(item, SNAPSHOT_ID))
                .message(Attrs.str(item, MESSAGE))
                .build();
    }
}

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
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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

    /**
     * 저장된 기록이 <b>아직 RUNNING 일 때만</b> 닫는다(설계 2026-09-30 §3.3). 락을 잡은 다음 작업이 죽은 기록을 정리할 때 쓴다.
     *
     * <p>조건이 없으면, 앞 작업이 락을 반납한 뒤·자기 기록을 쓰기 전의 틈에 다음 작업이 락을 잡고 이 기록을 볼 수 있다 — 그 순간엔
     * 방금 끝난 기록도 아직 RUNNING 으로 보인다(읽기는 최종 일관성인 {@link #findRecent} 다). 조건을 걸면 어느 쪽 쓰기가 먼저 닿든
     * 결과는 맞다: 이 닫기가 먼저 닿으면 앞 작업의 조건 없는 {@link #finish} 가 그 위에 실제 결과를 덮어쓰고, {@link #finish} 가
     * 먼저 닿으면 이 조건이 깨져({@link ConditionalCheckFailedException}) 빈 Mono 로 끝난다 — 닫기는 아무 일도 하지 않는다.
     */
    @Override
    public Mono<SyncRun> abandon(SyncRun run, String reason) {
        SyncRun closed = run.finished(SyncOutcome.failed(reason), clock.instant());
        return save(closed, "#status = :running", Map.of(":running", Attrs.s(SyncStatus.RUNNING.name())))
                .thenReturn(closed)
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }

    private Mono<Void> save(SyncRun run) {
        return save(run, null, Map.of());
    }

    private Mono<Void> save(SyncRun run, String conditionExpression, Map<String, AttributeValue> conditionValues) {
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

        PutItemRequest.Builder request = PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item);
        if (conditionExpression != null) {
            request.conditionExpression(conditionExpression)
                    .expressionAttributeNames(Map.of("#status", STATUS))
                    .expressionAttributeValues(conditionValues);
        }

        return Mono.fromFuture(() -> client.putItem(request.build())).then();
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
     * 이번 달·지난달 파티션의 RUNNING 기록을 모두, 최신순으로 준다(설계 2026-10-27 §12). {@link #findRecent} 와 같은 두 파티션 Query 에
     * {@code status = RUNNING} 거름식을 더했다 — Scan 이 아니다.
     *
     * <p><b>비용.</b> 거름식은 읽은 <b>뒤에</b> 걸러서 읽기 용량은 파티션 전체 크기만큼 든다. app-ldap 은 매시 회차마다 두 달치(약 1,500건)를 읽는다. 아이템이
     * 작아 읽기 용량은 수십 단위다. 지난달도 항상 읽는다 — 달이 바뀌기 직전에 시작해 죽은 기록이 거기 있을 수 있다. 응답이 1MB 를 넘으면 {@link Paginator} 가
     * 이어 읽는다(거름식 때문에 빈 쪽이 끼어도 마지막 키를 따라간다).
     */
    @Override
    public Flux<SyncRun> findRunning() {
        YearMonth thisMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));

        return queryMonth(thisMonth, true)
                .concatWith(queryMonth(thisMonth.minusMonths(1), true));
    }

    /**
     * 이번 달과 지난달 파티션에서 번호로 찾는다. 기록은 보관 기간(기본 30일) 뒤 사라지므로 그보다 앞선 달에는 없다.
     *
     * <p>파티션 하나가 한 달치 기록이다 — 매시 동기화면 약 720건이지 수십 건이 아니다. 그래도 파티션 하나의 Query 라 통째로 읽고, runId 로 찾는 인덱스를 따로 두지 않는다.
     * 이번 달에서 찾으면 {@code next()} 가 구독을 끊어 지난달은 읽지 않는다({@link #findRecent} 와 같은 이유). 같은 파티션을 app-ldap 의 {@code LastSuccessGauge} 가
     * 인스턴스마다 1분에 한 번 첫 쪽부터 읽는다(최신 성공을 만나면 멈춘다) — 읽기 비용이 문제가 되면 이 두 읽기부터 본다.
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
        return queryMonth(month, false);
    }

    /** @param runningOnly true 면 {@code status = RUNNING} 인 기록만 거른다 — 거름식은 읽은 뒤에 걸러, 읽기 용량은 달리지 않는다 */
    private Flux<SyncRun> queryMonth(YearMonth month, boolean runningOnly) {
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .scanIndexForward(false);
        if (runningOnly) {
            request.filterExpression("#status = :running")
                    .expressionAttributeNames(Map.of("#pk", Keys.PK, "#status", STATUS))
                    .expressionAttributeValues(Map.of(
                            ":pk", Attrs.s(Keys.syncRunPk(month)),
                            ":running", Attrs.s(SyncStatus.RUNNING.name())));
        } else {
            request.expressionAttributeNames(Map.of("#pk", Keys.PK))
                    .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.syncRunPk(month))));
        }

        return Paginator.queryAll(client, request.build()).map(this::toRun);
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

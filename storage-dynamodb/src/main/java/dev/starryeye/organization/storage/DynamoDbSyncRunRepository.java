package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.GuardHold;
import dev.starryeye.organization.core.model.ReactivationHold;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
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
    private static final String REQUESTED_BY = "requestedBy";
    private static final String GUARD_HOLD = "guardHold";
    private static final String REACTIVATION_HOLD = "reactivationHold";
    private static final String DIRECT_MEMBER_LINES = "directMemberLines";
    private static final String CHILD_LINES = "childLines";
    private static final String OTHER_LINES = "otherLines";
    private static final String USERS_GONE = "usersGone";
    private static final String USERS_INACTIVE = "usersInactive";
    private static final String USERS_LOSING_MEMBERSHIP = "usersLosingMembership";
    private static final String GROUPS_GONE = "groupsGone";
    private static final String GROUPS_LOSING_PARENT = "groupsLosingParent";
    private static final String COUNT = "count";
    private static final String IDS = "ids";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, String requestedBy) {
        Instant 지금 = clock.instant();
        SyncRun run = SyncRun.started(Keys.runId(지금, UUID.randomUUID()), source, trigger, 지금, requestedBy);
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
        Attrs.putIfPresent(item, REQUESTED_BY, run.requestedBy());
        if (run.guardHold() != null) {
            item.put(GUARD_HOLD, Attrs.m(guardHoldAttrs(run.guardHold())));
        }
        if (run.reactivationHold() != null) {
            item.put(REACTIVATION_HOLD, sampleAttr(run.reactivationHold().count(), run.reactivationHold().ids()));
        }

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
     * 번호에서 키를 다시 만들어 GetItem 한 번으로 찾는다 — 보관 기간 안이면 몇 달 전 것도 찾는다. 모양이 다른 번호는 DynamoDB 를 부르지 않고
     * 빈 결과다(설계 2026-11-25 I2).
     */
    @Override
    public Mono<SyncRun> findById(String runId) {
        return Keys.runIdStartedAt(runId)
                .map(startedAt -> Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                                .tableName(properties.getTableName())
                                .key(Map.of(
                                        Keys.PK, Attrs.s(Keys.syncRunPk(startedAt)),
                                        Keys.SK, Attrs.s(Keys.syncRunSk(startedAt, runId))))
                                .build()))
                        .filter(response -> response.hasItem() && !response.item().isEmpty())
                        .map(response -> toRun(response.item())))
                .orElseGet(Mono::empty);
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
                .requestedBy(Attrs.str(item, REQUESTED_BY))
                .guardHold(toGuardHold(item.get(GUARD_HOLD)))
                .reactivationHold(toReactivationHold(item.get(REACTIVATION_HOLD)))
                .build();
    }

    /** 가드 멈춤 내역을 M 속성의 안쪽 맵으로 푼다 — 예 목록은 빈 L 이어도 싣는다(설계 2026-11-20 §3.1 저장). */
    private static Map<String, AttributeValue> guardHoldAttrs(GuardHold hold) {
        Map<String, AttributeValue> map = new HashMap<>();
        map.put(DIRECT_MEMBER_LINES, Attrs.n(hold.directMemberLines()));
        map.put(CHILD_LINES, Attrs.n(hold.childLines()));
        map.put(OTHER_LINES, Attrs.n(hold.otherLines()));
        map.put(USERS_GONE, sampleAttr(hold.usersGone()));
        map.put(USERS_INACTIVE, sampleAttr(hold.usersInactive()));
        map.put(USERS_LOSING_MEMBERSHIP, sampleAttr(hold.usersLosingMembership()));
        map.put(GROUPS_GONE, sampleAttr(hold.groupsGone()));
        map.put(GROUPS_LOSING_PARENT, sampleAttr(hold.groupsLosingParent()));
        return map;
    }

    private static AttributeValue sampleAttr(GuardHold.Sample sample) {
        return sampleAttr(sample.count(), sample.ids());
    }

    /** {@code {count N, ids L of S}} 꼴 — 삭제 가드의 갈래와 재활성 보류 내역이 같이 쓴다. */
    private static AttributeValue sampleAttr(int count, List<String> ids) {
        return Attrs.m(Map.of(
                COUNT, Attrs.n(count),
                IDS, Attrs.l(ids.stream().map(Attrs::s).toList())));
    }

    /** 속성이 없거나 M 이 아니면 null — 보류하지 않은 회차와 옛 기록이다(설계 2026-11-24 R7). */
    private static ReactivationHold toReactivationHold(AttributeValue value) {
        if (value == null || !value.hasM()) {
            return null;
        }
        GuardHold.Sample sample = toSample(value);
        return new ReactivationHold(sample.count(), sample.ids());
    }

    /** 속성이 없거나 M 이 아니면 null — 내역이 없는 기록과 옛 기록이다. */
    private static GuardHold toGuardHold(AttributeValue value) {
        if (value == null || !value.hasM()) {
            return null;
        }
        Map<String, AttributeValue> map = value.m();
        return new GuardHold(
                Attrs.integer(map, DIRECT_MEMBER_LINES),
                Attrs.integer(map, CHILD_LINES),
                Attrs.integer(map, OTHER_LINES),
                toSample(map.get(USERS_GONE)),
                toSample(map.get(USERS_INACTIVE)),
                toSample(map.get(USERS_LOSING_MEMBERSHIP)),
                toSample(map.get(GROUPS_GONE)),
                toSample(map.get(GROUPS_LOSING_PARENT)));
    }

    private static GuardHold.Sample toSample(AttributeValue value) {
        if (value == null || !value.hasM()) {
            return new GuardHold.Sample(0, List.of());
        }
        AttributeValue ids = value.m().get(IDS);
        List<String> list = ids == null || !ids.hasL() ? List.of() : ids.l().stream().map(AttributeValue::s).toList();
        return new GuardHold.Sample(Attrs.integer(value.m(), COUNT), list);
    }
}

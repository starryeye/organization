package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeSnapshotSource;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** LDAP 동기화·재적재의 이력(설계 2026-10-10 §4.2·§4.4·§4.5) — 실패·중단된 회차도 그때까지 한 변경을 남긴다(설계 2026-10-15). */
class LdapSyncAuditTest {

    private static final Instant 고정시각 = Instant.parse("2026-10-10T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final MemberRef 유령 = MemberRef.user("ghost");
    private static final RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV");
    private static final RelationTuple 유령_개발 = RelationTuple.directMember("ghost", "DEV");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private FakeAuditLog auditLog;
    private final AtomicInteger 빠진_줄_지표 = new AtomicInteger();
    private Clock clock;
    private AuditRecorder audit;
    private DeletionGuard guard;
    private FullSyncUseCase sync;
    private RebuildUseCase rebuild;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        runs = new FakeSyncRunRepository(고정시각);
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        clock = Clock.fixed(고정시각, ZoneOffset.UTC);
        audit = new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, clock);
        SyncJobs jobs = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1), audit);
        guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        sync = new FullSyncUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
        rebuild = new RebuildUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", true);
    }

    /** 직원들과, 그들을 모두 멤버로 둔 조직 DEV. */
    private static DirectorySnapshot 조직도(DirectoryUser... users) {
        Map<String, DirectoryUser> byId = new LinkedHashMap<>();
        for (DirectoryUser user : users) {
            byId.put(user.id(), user);
        }
        Set<MemberRef> members = byId.keySet().stream().map(MemberRef::user).collect(Collectors.toSet());
        return new DirectorySnapshot(byId, Map.of("DEV", new DirectoryGroup("DEV", "cn=DEV", "개발", members)));
    }

    private SyncRun 동기화한다(SyncTrigger trigger) {
        SyncRun started = sync.start(trigger, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    /** 가드를 넘기는 재적재 — 가드는 이 테스트의 관심이 아니다. */
    private SyncRun 재적재한다() {
        SyncRun started = rebuild.start(true, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("동기화는 회차 id 를 원인으로 조직도 변경과 쓴 튜플을 남긴다")
    void 동기화는_회차_id_로_남긴다() {
        // given
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).singleElement().satisfies(event ->
                assertThat(event.cause()).isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, run.runId())));
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.created()).isTrue();
        assertThat(김의_줄.joined()).containsExactly("DEV");
        assertThat(김의_줄.granted()).containsExactly(김_개발);
        AuditEntry 개발의_줄 = auditLog.lastOf(MemberRef.group("DEV"));
        assertThat(개발의_줄.created()).isTrue();
        assertThat(개발의_줄.membersAdded()).containsExactly(김);
    }

    @Test
    @DisplayName("튜플이 그대로인 회차(변경 없음)도 조직도가 바뀌었으면 남긴다")
    void 변경_없는_회차도_조직도_변경은_남긴다() {
        // given
        source.willReturn(조직도(직원("kim")));
        동기화한다(SyncTrigger.SCHEDULED);
        source.willReturn(조직도(직원("kim").withDisplayName("김철수")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.message()).isEqualTo("변경 없음");
        assertThat(auditLog.events).hasSize(2);
        assertThat(auditLog.events.get(1).cause().id()).isEqualTo(run.runId());
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.attributes()).containsExactly(new AttributeChange("displayName", "kim", "김철수"));
        assertThat(김의_줄.granted()).isEmpty();
    }

    @Test
    @DisplayName("같은 조직도를 다시 동기화하면 남길 것이 없어 쓰지 않는다")
    void 같은_조직도를_다시_동기화하면_남길_것이_없다() {
        // given
        source.willReturn(조직도(직원("kim")));
        동기화한다(SyncTrigger.SCHEDULED);

        // when
        동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(auditLog.events).hasSize(1);
    }

    @Test
    @DisplayName("삭제 가드에 걸린 회차는 남기지 않는다 — 커밋한 것이 없다")
    void 삭제_가드에_걸리면_남기지_않는다() {
        // given — 기준 20건, LDAP 이 0건을 돌려줘 전건 삭제 상황
        Set<RelationTuple> 기준 = IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV"))
                .collect(Collectors.toSet());
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 기준)).block();
        source.willReturn(DirectorySnapshot.empty());

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("이력은 락을 반납한 뒤에 쓴다")
    void 기록은_락을_반납한_뒤에_쓴다() {
        // given
        AtomicBoolean 쓸때_락을_쥠 = new AtomicBoolean(true);
        auditLog.onWrite = () -> 쓸때_락을_쥠.set(lock.isHeld());
        source.willReturn(조직도(직원("kim")));

        // when
        동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(auditLog.events).hasSize(1);
        assertThat(쓸때_락을_쥠).isFalse();
    }

    /** 반납하는 순간 시계가 가는 락으로 동기화한다 — 이력과 스냅샷이 그 락의 시계를 본다. */
    private SyncRun 시계가_가는_락으로_동기화한다(ClockAdvancingLock 시계가_가는_락, SyncTrigger trigger) {
        Clock 시계 = 시계가_가는_락.clock();
        SyncJobs jobs = new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1),
                new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, 시계));
        FullSyncUseCase 시계를_보는_동기화 = new FullSyncUseCase(source, snapshots, state, writer, new FakeTupleScanner(writer),
                new DeletionGuard(DeletionGuardPolicy.defaults()), jobs, 시계);
        SyncRun started = 시계를_보는_동기화.start(trigger, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("동기화 이력의 시각은 반납 뒤 쓴 시각이 아니라 락 안에서 커밋한 시각이다")
    void 동기화_시각은_커밋한_시각이다() {
        // given — 반납하는 순간 시계가 1분 간다. 이력은 반납 뒤에 쓴다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(고정시각);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 시계가_가는_락으로_동기화한다(시계가_가는_락, SyncTrigger.SCHEDULED);

        // then — 반납이 시계를 옮겼고, 이력은 그 전 시각이다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(고정시각.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(고정시각));
    }

    @Test
    @DisplayName("장부 맞추기 이력의 시각도 락 안에서 커밋한 시각이다")
    void 장부_맞추기_시각은_커밋한_시각이다() {
        // given — 지난 회차가 쓰다 멈췄다. 반납하는 순간 시계가 1분 간다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(고정시각);
        writer.stored.add(유령_개발);
        snapshots.writing.set(true);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 시계가_가는_락으로_동기화한다(시계가_가는_락, SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(고정시각.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.cause().kind()).isEqualTo(AuditKind.LEDGER_ALIGNMENT);
            assertThat(event.at()).isEqualTo(고정시각);
        });
    }

    @Test
    @DisplayName("이력 쓰기가 실패해도 회차는 성공하고, 회차 메시지와 지표에 빠진 줄이 남는다")
    void 이력_쓰기가_실패해도_회차는_성공하고_메시지에_남는다() {
        // given
        auditLog.failWith = new IllegalStateException("DynamoDB 장애");
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 줄은 kim 과 DEV 둘
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).startsWith("감사 기록 2건 실패");
        assertThat(빠진_줄_지표).hasValue(2);
        assertThat(state.users).containsKey("kim");
    }

    @Test
    @DisplayName("쓰기가 차단기로 멈춘 회차도 실제로 쓴 것을 남긴다")
    void 멈춘_회차도_쓴_것을_남긴다() {
        // given
        writer.abortWhen(delta -> true);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("기록 중 표시가 남은 회차는 장부 맞추기로 남기고, 지운 줄만 적는다")
    void 장부_맞추기는_지운_줄만() {
        // given — 지난 회차가 쓰다 멈췄고 장부에 찌꺼기가 있다. 가드는 이 테스트의 관심이 아니라 FORCED 로 넘긴다
        writer.stored.add(유령_개발);
        snapshots.writing.set(true);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.LEDGER_ALIGNMENT, run.runId())));
        assertThat(auditLog.lastOf(유령).revoked()).containsExactly(유령_개발);
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.created()).isTrue();
        assertThat(김의_줄.granted()).isEmpty();
    }

    @Test
    @DisplayName("기준선이 없는 첫 회차는 종류가 동기화 그대로이고 쓴 줄을 부여로, 지운 줄을 회수로 남긴다")
    void 기준선이_없는_첫_회차는_쓴_줄을_부여로_남긴다() {
        // given — 저장한 스냅샷이 없고 장부에는 찌꺼기가 있다. 가드는 이 테스트의 관심이 아니다(기준 10건 미만이라 걸리지 않는다)
        writer.stored.add(유령_개발);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 장부 맞추기와 달리 첫 적재의 부여 이력이 사라지지 않는다(설계 2026-10-10 §4.2)
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).singleElement().satisfies(event ->
                assertThat(event.cause()).isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, run.runId())));
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.created()).isTrue();
        assertThat(김의_줄.granted()).containsExactly(김_개발);
        assertThat(auditLog.lastOf(유령).revoked()).containsExactly(유령_개발);
        assertThat(auditLog.lastOf(유령).granted()).isEmpty();
    }

    @Test
    @DisplayName("재적재는 재적재로 남기고, 지운 줄만 적는다")
    void 재적재는_지운_줄만() {
        // given
        writer.stored.add(유령_개발);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.REBUILD, run.runId())));
        assertThat(auditLog.lastOf(유령).revoked()).containsExactly(유령_개발);
        assertThat(auditLog.lastOf(김).granted()).isEmpty();
    }

    @Test
    @DisplayName("스냅샷 저장이 실패한 회차도 OpenFGA 에 쓴 튜플을 남긴다 — 현재상태는 바뀌지 않아 조직도 변경은 없다")
    void 스냅샷_저장이_실패해도_쓴_튜플을_남긴다() {
        // given
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패"));
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, run.runId())));
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.granted()).containsExactly(김_개발);
        assertThat(김의_줄.created()).isFalse();
    }

    @Test
    @DisplayName("현재상태 교체가 도중에 실패한 회차도 쓴 튜플과 그때까지 끝난 조직도 변경을 남긴다")
    void 현재상태_교체가_도중에_실패해도_끝난_것을_남긴다() {
        // given — 직원 kim 을 쓴 뒤, 조직 DEV 를 쓰기 전에 끊긴다
        state.failReplaceWithAfter = 1;
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.created()).isTrue();
        assertThat(김의_줄.granted()).containsExactly(김_개발);
        assertThat(김의_줄.joined()).as("DEV 의 멤버 줄은 쓰지 못했다").isEmpty();
        assertThat(auditLog.entriesOf(MemberRef.group("DEV"))).isEmpty();
    }

    @Test
    @DisplayName("쓰는 도중 리스를 잃어 멈춘 회차도 그때까지 끝난 쓰기를 남긴다")
    void 쓰는_도중_리스를_잃어도_끝난_쓰기를_남긴다() {
        // given — 쓰기 묶음은 끝났는데 전체 응답이 늦는 사이 리스를 잃는다
        SyncJobs 짧게_갱신하는_잡스 = new SyncJobs(runs, lock, Duration.ofMillis(50), LockObserver.NOOP, Duration.ofMinutes(1),
                audit);
        FullSyncUseCase 동기화 = new FullSyncUseCase(source, snapshots, state, writer, new FakeTupleScanner(writer), guard,
                짧게_갱신하는_잡스, clock);
        writer.delay = Duration.ofMinutes(10);
        writer.onApply(() -> lock.failRenew = true);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun started = 동기화.start(SyncTrigger.SCHEDULED, ignored -> {
        }).block();
        SyncRun run = runs.awaitFinished(started.runId());

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("리스");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("장부 맞추기가 현재상태 교체에 실패해도 지운 줄을 장부 맞추기로 남긴다")
    void 장부_맞추기가_실패해도_지운_줄을_남긴다() {
        // given — 지난 회차가 쓰다 멈췄고 장부에 찌꺼기가 있다. 맞춘 뒤 현재상태 교체가 곧바로 실패한다
        writer.stored.add(유령_개발);
        snapshots.writing.set(true);
        state.failReplaceWithAfter = 0;
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.LEDGER_ALIGNMENT, run.runId())));
        assertThat(auditLog.lastOf(유령).revoked()).containsExactly(유령_개발);
        assertThat(auditLog.entriesOf(김)).isEmpty();
    }

    @Test
    @DisplayName("재적재가 스냅샷 저장에 실패해도 지운 줄을 재적재로 남긴다")
    void 재적재가_스냅샷_저장에_실패해도_지운_줄을_남긴다() {
        // given
        writer.stored.add(유령_개발);
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패"));
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.REBUILD, run.runId())));
        assertThat(auditLog.lastOf(유령).revoked()).containsExactly(유령_개발);
    }
}

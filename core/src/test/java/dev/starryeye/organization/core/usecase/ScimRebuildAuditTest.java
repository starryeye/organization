package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** SCIM 재적재의 이력(설계 2026-10-10 §4.2) — tuples 는 지운 줄만, wipe 는 지운 조직도와 줄 전부. */
class ScimRebuildAuditTest {

    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final RelationTuple 김_백엔드 = RelationTuple.directMember("kim", "DEV002");
    private static final RelationTuple 백엔드_개발본부 = RelationTuple.child("DEV002", "DEV001");
    private static final RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private FakeAuditLog auditLog;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(NOW);
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        useCase = new ScimRebuildUseCase(state, writer, new FakeTupleScanner(writer), new FakeSnapshotRepository(),
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                clock, new AuditRecorder(auditLog, AuditObserver.NOOP, clock));
    }

    private void 조직도를_심는다() {
        state.saveUser(new DirectoryUser("kim", "emp-kim", "kim", "김", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드", Set.of(김))).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(MemberRef.group("DEV002")))).block();
    }

    private SyncRun 재적재한다(ScimRebuildMode mode) {
        SyncRun started = useCase.start(mode).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("tuples 모드는 회차 id 로, 지운 찌꺼기 줄만 남긴다 — 쓴 줄은 남기지 않는다")
    void tuples_모드는_지운_줄만() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.cause()).isEqualTo(AuditCause.run(SyncSource.SCIM, AuditKind.REBUILD, run.runId()));
            assertThat(event.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.subject()).isEqualTo(MemberRef.user("ghost"));
                assertThat(entry.revoked()).containsExactly(찌꺼기);
            });
        });
    }

    @Test
    @DisplayName("wipe 는 지운 직원·조직과 지운 줄 전부를 남긴다")
    void wipe_는_전부_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 백엔드_개발본부));

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.deleted()).isTrue();
        assertThat(김의_줄.left()).containsExactly("DEV002");
        assertThat(김의_줄.revoked()).containsExactly(김_백엔드);
        AuditEntry 백엔드의_줄 = auditLog.lastOf(MemberRef.group("DEV002"));
        assertThat(백엔드의_줄.deleted()).isTrue();
        assertThat(백엔드의_줄.membersRemoved()).containsExactly(김);
        assertThat(백엔드의_줄.left()).containsExactly("DEV001");
        assertThat(백엔드의_줄.revoked()).containsExactly(백엔드_개발본부);
        assertThat(auditLog.lastOf(MemberRef.group("DEV001")).deleted()).isTrue();
    }

    @Test
    @DisplayName("재적재의 이력은 락을 반납한 뒤에 쓴다")
    void 기록은_락을_반납한_뒤에_쓴다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);
        AtomicBoolean 쓸때_락을_쥠 = new AtomicBoolean(true);
        auditLog.onWrite = () -> 쓸때_락을_쥠.set(lock.isHeld());

        // when
        재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(auditLog.events).hasSize(1);
        assertThat(쓸때_락을_쥠).isFalse();
    }

    /** 반납하는 순간 시계가 가는 락으로 재적재한다 — 이력과 스냅샷이 그 락의 시계를 본다. */
    private SyncRun 시계가_가는_락으로_재적재한다(ClockAdvancingLock 시계가_가는_락, ScimRebuildMode mode) {
        Clock 시계 = 시계가_가는_락.clock();
        ScimRebuildUseCase 시계를_보는_재적재 = new ScimRebuildUseCase(state, writer, new FakeTupleScanner(writer),
                new FakeSnapshotRepository(),
                new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                시계, new AuditRecorder(auditLog, AuditObserver.NOOP, 시계));
        SyncRun started = 시계를_보는_재적재.start(mode).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("tuples 모드 이력의 시각은 반납 뒤 쓴 시각이 아니라 락 안에서 끝낸 시각이다")
    void tuples_모드_시각은_락_안의_시각이다() {
        // given — 반납하는 순간 시계가 1분 간다. 이력은 반납 뒤에 쓴다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(NOW);
        조직도를_심는다();
        writer.stored.add(찌꺼기);

        // when
        SyncRun run = 시계가_가는_락으로_재적재한다(시계가_가는_락, ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(NOW.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(NOW));
    }

    @Test
    @DisplayName("wipe 이력의 시각은 반납 뒤 쓴 시각이 아니라 락 안에서 조직도를 지운 시각이다")
    void wipe_시각은_락_안의_시각이다() {
        // given — 반납하는 순간 시계가 1분 간다. 이력은 반납 뒤에 쓴다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(NOW);
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 백엔드_개발본부));

        // when
        SyncRun run = 시계가_가는_락으로_재적재한다(시계가_가는_락, ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(NOW.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(NOW));
    }
}

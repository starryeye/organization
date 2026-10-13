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

/** LDAP 동기화·재적재의 이력(설계 2026-10-10 §4.2·§4.4·§4.5). */
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
        Clock clock = Clock.fixed(고정시각, ZoneOffset.UTC);
        AuditRecorder audit = new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, clock);
        SyncJobs jobs = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1));
        DeletionGuard guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        sync = new FullSyncUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock, audit);
        rebuild = new RebuildUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock, audit);
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
}

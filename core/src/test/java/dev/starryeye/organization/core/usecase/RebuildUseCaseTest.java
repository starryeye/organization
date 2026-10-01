package dev.starryeye.organization.core.usecase;

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
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * app-ldap 재적재 — 장부를 버리지 않고 안에서 청소한다 (설계 2026-09-29 §3). 옛 snapshot·store 두 모드를 하나로 합쳤다.
 */
class RebuildUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-14T03:00:00Z");
    private static final RelationTuple 김_백엔드 = RelationTuple.directMember("kim", "DEV002");
    private static final RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleScanner scanner;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private RebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        scanner = new FakeTupleScanner(writer);
        runs = new FakeSyncRunRepository(고정시각);
        lock = new FakeMutationLock();
        useCase = new RebuildUseCase(source, snapshots, state, writer, scanner,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    private static DirectorySnapshot 조직도(String userId, String groupCode) {
        return new DirectorySnapshot(
                Map.of(userId, new DirectoryUser(userId, "uid=" + userId, userId, userId, null, true)),
                Map.of(groupCode, new DirectoryGroup(groupCode, "cn=" + groupCode, "백엔드팀",
                        Set.of(MemberRef.user(userId)))));
    }

    /** 재적재를 걸고 끝날 때까지 기다린다 — 재적재는 요청과 떼어 돈다(설계 §4). */
    private SyncRun 재적재한다() {
        return 재적재한다(false);
    }

    private SyncRun 재적재한다(boolean force) {
        SyncRun started = useCase.start(force, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("LDAP 을 읽어 있어야 할 줄을 쓰고, 장부를 훑어 없어야 할 줄만 지운다 — 스냅샷에 없던 찌꺼기도")
    void 장부_안에서_청소한다() {
        // given — 장부에 kim 의 권한과, 어느 스냅샷에도 없는 찌꺼기가 있다
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));

        // when
        SyncRun run = 재적재한다();

        // then — 옛 snapshot 모드는 스냅샷에 없는 줄을 지우지 못했다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(writer.stored).containsExactly(김_백엔드);
        assertThat(writer.deleted).containsExactly(찌꺼기);
        assertThat(run.deletedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("끝나면 새 스냅샷과 현재상태가 LDAP 대로 남고, 스냅샷을 비우지 않는다")
    void 스냅샷과_현재상태가_남는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.add(찌꺼기);

        // when
        재적재한다();

        // then
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.LDAP);
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(김_백엔드);
        assertThat(state.users).containsOnlyKeys("kim");
        assertThat(state.groups).containsOnlyKeys("DEV002");
    }

    @Test
    @DisplayName("장부를 비우는 순간이 없다 — 쓰고 지우는 내내 기존 권한이 장부에 있다")
    void 장부를_비우지_않는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));
        List<Boolean> 쓸때마다_있었나 = new CopyOnWriteArrayList<>();
        writer.onApply(() -> 쓸때마다_있었나.add(writer.stored.contains(김_백엔드)));

        // when
        재적재한다();

        // then — 옛 store 모드는 장부를 지우고 다시 만들어, 그동안 모든 권한 질의가 false 였다
        assertThat(쓸때마다_있었나).isNotEmpty().doesNotContain(false);
        assertThat(writer.stored).contains(김_백엔드);
    }

    @Test
    @DisplayName("LDAP 읽기가 실패하면 장부에 아무것도 쓰거나 지우지 않고 FAILED 다 — 비운 뒤 읽기 실패로 전사 권한 0 이 되지 않는다")
    void 읽기가_실패하면_장부를_건드리지_않는다() {
        // given
        source.willFail(new IllegalStateException("LDAP 연결 실패"));
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("LDAP 연결 실패");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(scanner.scanCount).hasValue(0);
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 찌꺼기);
        assertThat(snapshots.saved).isEmpty();
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("기준선 스냅샷이 깨져 있어도 재적재는 스냅샷을 읽지 않고 끝난다 — 깨진 기준선을 고치는 수단이다")
    void 깨진_기준선에서도_끝난다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 메타가 없습니다 — POST /admin/sync/rebuild 로 복구하세요"));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(김_백엔드);
    }

    @Test
    @DisplayName("지우지 못한 줄은 새 스냅샷에 남는다 — 다음 동기화가 그 줄을 다시 지운다")
    void 지우지_못한_줄은_스냅샷에_남는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.add(찌꺼기);
        writer.failFor(찌꺼기::equals);

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 찌꺼기);
    }

    @Test
    @DisplayName("쓰지 못한 줄은 새 스냅샷에서 빠진다 — 다음 동기화가 그 줄을 다시 쓴다")
    void 쓰지_못한_줄은_스냅샷에서_빠진다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.failFor(김_백엔드::equals);

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("지우기가 연속 실패 차단기로 멈추면 FAILED 지만 스냅샷은 남는다 — 쓴 줄과 지우지 못한 줄을 담는다")
    void 지우기가_차단기로_멈춰도_스냅샷을_남긴다() {
        // given — 찌꺼기 지우기가 실패한 채 멈춘다
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.add(찌꺼기);
        writer.failFor(찌꺼기::equals);
        writer.abortWhen(delta -> !delta.toDelete().isEmpty());

        // when
        SyncRun run = 재적재한다();

        // then — 찌꺼기는 장부에 아직 있으니 스냅샷에도 있어야 다음 동기화가 다시 지운다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("연속 실패로 멈췄다");
        assertThat(run.snapshotId()).isEqualTo(snapshots.saved.get(0).id());
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 찌꺼기);
        assertThat(state.users).containsOnlyKeys("kim");
    }

    @Test
    @DisplayName("쓰기가 연속 실패 차단기로 멈추면 장부를 훑지 않고 스냅샷도 만들지 않은 채 FAILED 다 — 지울 줄을 모른다")
    void 쓰기가_차단기로_멈추면_훑지_않는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.add(찌꺼기);
        writer.abortWhen(delta -> !delta.toWrite().isEmpty());

        // when
        SyncRun run = 재적재한다();

        // then — 쓰기 단계에서 멈췄으니 3단계(훑어서 지우기)로 가지 않는다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("연속 실패로 멈췄다");
        assertThat(scanner.scanCount).hasValue(0);
        assertThat(snapshots.saved).isEmpty();
        assertThat(state.users).isEmpty();
        assertThat(writer.stored).contains(찌꺼기);
        assertThat(snapshots.writing).as("표시가 남아 다음 동기화가 장부를 훑어 맞춘다").isTrue();
    }

    @Test
    @DisplayName("RUNNING 기록을 곧바로 주고, 끝나면 락 반납과 onFinished 를 한 번씩 부른다")
    void 기록을_곧바로_주고_끝나면_반납한다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        AtomicReference<SyncRun> 끝남 = new AtomicReference<>();

        // when
        SyncRun started = useCase.start(false, 끝남::set).block();

        // then
        assertThat(started.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(started.trigger()).isEqualTo(SyncTrigger.REBUILD);
        SyncRun finished = runs.awaitFinished(started.runId());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(끝남.get()).isEqualTo(finished));
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("재적재도 쓰기 전에 '기록 중' 표시를 남기고, 끝나면 표시가 사라진다 — 중간에 멈추면 다음 동기화가 맞춘다")
    void 재적재도_표시를_남긴다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        AtomicBoolean 쓸때_표시 = new AtomicBoolean();
        writer.onApply(() -> 쓸때_표시.set(snapshots.writing.get()));

        // when
        재적재한다();

        // then
        assertThat(쓸때_표시).isTrue();
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("LDAP 이 0명을 돌려주면 재적재는 장부를 지우지 않고 ABORTED 다 — 설정 실수로 전사 권한이 지워지지 않게")
    void 빈_조직도면_재적재가_지우지_않는다() {
        // given — 장부에 20줄이 있는데 LDAP 이 아무도 돌려주지 않는다
        var 장부 = IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
        writer.stored.addAll(장부);
        source.willReturn(DirectorySnapshot.empty());

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("임계치").contains("force=true");
        assertThat(writer.stored).hasSize(20);
        assertThat(snapshots.saved).isEmpty();
        assertThat(snapshots.writing).as("표시가 남아 다음 회차도 다시 확인한다").isTrue();
    }

    @Test
    @DisplayName("force=true 면 재적재의 가드를 건너뛰고 지운다")
    void 강제면_재적재가_지운다() {
        // given
        writer.stored.addAll(IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet()));
        source.willReturn(DirectorySnapshot.empty());

        // when
        SyncRun run = 재적재한다(true);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).isEmpty();
    }
}

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
import dev.starryeye.organization.core.guard.ForceAllowance;
import dev.starryeye.organization.core.guard.ReactivationGuard;
import dev.starryeye.organization.core.guard.ReactivationGuardPolicy;
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
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
        useCase = 유스케이스(ReactivationGuardPolicy.defaults());
    }

    private RebuildUseCase 유스케이스(ReactivationGuardPolicy 재활성_정책) {
        return new RebuildUseCase(source, snapshots, state, writer, scanner,
                new DeletionGuard(DeletionGuardPolicy.defaults()), new ReactivationGuard(재활성_정책),
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
        SyncRun started = useCase.start(run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    /** 허용 수 N 을 주어 강제로 재적재하고 끝날 때까지 기다린다 — 강제 재적재는 허용 수 입구로만 든다(설계 2026-11-20 F1). */
    private SyncRun 강제로_재적재한다(int maxDeletes) {
        return 강제로_재적재한다(new ForceAllowance(maxDeletes));
    }

    /** 허용 수(지울 줄·재활성 가운데 준 것)로 강제로 재적재하고 끝날 때까지 기다린다(설계 2026-11-24 R9). */
    private SyncRun 강제로_재적재한다(ForceAllowance allowance) {
        SyncRun started = useCase.startForced(allowance, run -> {
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
    @DisplayName("기준선 스냅샷이 깨져 있어도 재적재는 끝난다 — 깨진 기준선을 고치는 수단이다")
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
    @DisplayName("장부 맞추기의 지우기가 차단기로 멈추면 현재상태를 디렉터리대로 교체했으므로 메시지 끝에 그렇게 적는다")
    void 장부_맞추기의_지우기가_차단기로_멈추면_교체했다고_적는다() {
        // given — 찌꺼기 지우기가 실패한 채 멈춘다. 스냅샷을 남기고 현재상태는 LDAP 대로 바뀐다
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.add(찌꺼기);
        writer.failFor(찌꺼기::equals);
        writer.abortWhen(delta -> !delta.toDelete().isEmpty());

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(state.users).as("현재상태를 실제로 교체했다").containsOnlyKeys("kim");
        assertThat(run.message())
                .contains("연속 실패로 멈췄다")
                .endsWith(" — 현재상태는 디렉터리대로 교체했다");
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
        assertThat(run.message()).contains("연속 실패로 멈췄다").doesNotContain("현재상태는 디렉터리대로 교체했다");
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
        SyncRun started = useCase.start(끝남::set).block();

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
    @DisplayName("재적재가 전부 보류하면 실행 기록에 내역이 있다 — 훑은 장부의 찌꺼기가 사라진 직원·조직으로 나온다")
    void 재적재가_전부_보류하면_내역이_있다() {
        // given — 장부에 user0~19 의 DEV002 소속과 없는 조직 OLD 의 소속 2줄이 있고, LDAP 은 kim 한 명만 돌려준다
        writer.stored.addAll(IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet()));
        writer.stored.add(RelationTuple.directMember("user0", "OLD"));
        writer.stored.add(RelationTuple.directMember("user1", "OLD"));
        source.willReturn(조직도("kim", "DEV002"));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.guardHold()).isNotNull();
        assertThat(run.guardHold().usersGone().count()).isEqualTo(20);
        assertThat(run.guardHold().groupsGone().count()).isEqualTo(1);
        assertThat(run.guardHold().groupsGone().ids()).containsExactly("OLD");
        assertThat(run.guardHold().totalLines()).isEqualTo(22);
        assertThat(run.message()).contains("삭제 대상 22건");
    }

    @Test
    @DisplayName("재적재가 비활성 회수만 반영해도 내역은 훑은 장부에서 지우려던 전체다")
    void 재적재가_비활성_회수만_반영해도_내역이_있다() {
        // given — 장부에 user0~19 소속 20줄. 디렉터리에는 user1~5 만 활성으로 남고 user0 은 막혔다
        writer.stored.addAll(IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet()));
        Map<String, DirectoryUser> users = new java.util.LinkedHashMap<>();
        IntStream.range(1, 6).forEach(i -> users.put("user" + i,
                new DirectoryUser("user" + i, "uid=user" + i, "user" + i, "user" + i, null, true)));
        users.put("user0", new DirectoryUser("user0", "uid=user0", "user0", "user0", null, false));
        Set<MemberRef> members = users.keySet().stream().map(MemberRef::user).collect(Collectors.toSet());
        source.willReturn(new DirectorySnapshot(users,
                Map.of("DEV002", new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀", members))));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("비활성 회수 1건만 반영");
        assertThat(run.guardHold()).isNotNull();
        assertThat(run.guardHold().usersInactive().count()).isEqualTo(run.deletedCount()).isEqualTo(1);
        assertThat(run.guardHold().usersGone().count()).isEqualTo(14);
        assertThat(run.guardHold().totalLines()).isEqualTo(15);
    }

    @Test
    @DisplayName("강제 재적재는 지울 줄이 허용 수 안이면 비율을 넘어도 지우고, 결론 앞에 허용 수를 적는다")
    void 강제_재적재는_허용_수_안이면_지운다() {
        // given — 장부 20줄을 모두 지운다(100%). 허용 수는 그 20줄이다
        writer.stored.addAll(IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet()));
        source.willReturn(DirectorySnapshot.empty());

        // when
        SyncRun run = 강제로_재적재한다(20);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).isEmpty();
        assertThat(run.message()).isEqualTo("강제 실행 — 지울 줄 허용 20건");
    }

    // 깨뜨릴 변경: 강제 재적재의 장부 맞추기가 DeleteCheck.NONE 으로 가드 없이 지우기
    @Test
    @DisplayName("강제 재적재도 지울 줄이 허용 수를 넘으면 장부의 후보를 지우지 않고 ABORTED 이며 멈춤 내역이 있다")
    void 강제_재적재가_허용_수를_넘으면_장부의_후보를_지우지_않는다() {
        // given — 장부 20줄을 모두 지우려는데 허용 수는 19건이다
        writer.stored.addAll(IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet()));
        source.willReturn(DirectorySnapshot.empty());

        // when
        SyncRun run = 강제로_재적재한다(19);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.FORCED_REBUILD);
        assertThat(run.message())
                .startsWith("강제 실행 — 지울 줄 허용 19건 / 삭제 대상 20건(훑은 장부 20건")
                .contains("상한 19건을 초과했습니다");
        assertThat(writer.stored).as("장부의 후보를 그대로 둔다").hasSize(20);
        assertThat(writer.deleted).isEmpty();
        assertThat(run.guardHold()).isNotNull();
        assertThat(run.guardHold().totalLines()).isEqualTo(20);
        assertThat(snapshots.saved).isEmpty();
        assertThat(snapshots.writing).as("표시가 남아 다음 회차도 다시 확인한다").isTrue();
    }

    @Test
    @DisplayName("재적재가 전부 보류로 끝나도 D 를 이미 쓴 만큼 쓴 수를 남긴다 — 지운 수는 0, 스냅샷은 없다")
    void 전부_보류한_재적재도_쓴_수를_남긴다() {
        // given — 장부에 20줄이 있는데 LDAP 은 kim 한 명만 돌려준다. kim 은 쓰고, 나머지 20줄을 지우려다 가드가 막는다
        writer.stored.addAll(IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet()));
        source.willReturn(조직도("kim", "DEV002"));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.writtenCount()).as("쓴 D 의 줄 수").isEqualTo(1);
        assertThat(run.deletedCount()).isZero();
        assertThat(run.failureCount()).isZero();
        assertThat(run.snapshotId()).isNull();
        assertThat(writer.stored).as("지우지 않았다").hasSize(21);
    }

    @Test
    @DisplayName("강제 재적재는 트리거 FORCED_REBUILD 로 남는다 — 락 용도는 REBUILD 그대로다")
    void force_재적재는_FORCED_REBUILD_로_남는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));

        // when
        SyncRun started = useCase.startForced(new ForceAllowance(1), run -> {
        }).block();
        SyncRun finished = runs.awaitFinished(started.runId());

        // then — 시작할 때 연 기록부터 FORCED_REBUILD 다
        assertThat(started.trigger()).isEqualTo(SyncTrigger.FORCED_REBUILD);
        assertThat(finished.trigger()).isEqualTo(SyncTrigger.FORCED_REBUILD);
        assertThat(lock.시도한_용도).containsOnly(MutationLock.LockPurpose.REBUILD);
    }

    @Test
    @DisplayName("force 없는 재적재는 트리거 REBUILD 다")
    void force_없는_재적재는_REBUILD_다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
    }

    // ---------- 재활성 가드 (설계 2026-11-24 R8) ----------

    /** 상한 1명 — 재활성 둘이면 보류한다. 기준이 10명 미만이라 비율은 보지 않는다. */
    private static final ReactivationGuardPolicy 상한_1명 = new ReactivationGuardPolicy(true, 0.3, 10, 1);
    private static final RelationTuple 막힌_가 = RelationTuple.directMember("blocked1", "DEV002");
    private static final RelationTuple 막힌_나 = RelationTuple.directMember("blocked2", "DEV002");

    /** 활성 직원과 막힌 직원이 DEV002 에 함께 있는 조직도. */
    private static DirectorySnapshot 조직도(Set<String> 활성, Set<String> 비활성) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        활성.forEach(id -> users.put(id, new DirectoryUser(id, "uid=" + id, id, id, null, true)));
        비활성.forEach(id -> users.put(id, new DirectoryUser(id, "uid=" + id, id, id, null, false)));
        Set<MemberRef> members = users.keySet().stream().map(MemberRef::user).collect(Collectors.toSet());
        return new DirectorySnapshot(users, Map.of("DEV002", new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀", members)));
    }

    /** 직전 스냅샷 — 장부에 kim 의 줄이 있고, 막혀 있던 blocked1·blocked2 의 소속 줄이 I 에 있다. */
    private void 막힌_둘이_든_스냅샷을_둔다() {
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, Set.of(김_백엔드), Set.of(), Set.of(막힌_가, 막힌_나)))
                .block();
        writer.stored.add(김_백엔드);
    }

    // 깨뜨릴 변경: 재적재가 판정하지 않거나, 판정 뒤 매핑·현재상태·스냅샷 I 에 그림′ 대신 받은 그림을 넘기기
    @Test
    @DisplayName("재적재도 직전 스냅샷이 있으면 재활성을 판정해 보류한다 — 장부 맞추기 커밋의 스냅샷 I 는 그림′ 의 것이다")
    void 재적재도_스냅샷이_있으면_재활성을_보류한다() {
        // given
        useCase = 유스케이스(상한_1명);
        막힌_둘이_든_스냅샷을_둔다();
        source.willReturn(조직도(Set.of("kim", "blocked1", "blocked2"), Set.of()));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(run.reactivationHold()).isNotNull();
        assertThat(run.reactivationHold().count()).isEqualTo(2);
        assertThat(run.reactivationHold().ids()).containsExactly("blocked1", "blocked2");
        assertThat(run.message()).isEqualTo("재활성 가드 발동 — 직원 2명의 재활성을 보류(비활성 그대로 둠) / "
                + "재활성 대상 2명(직전 비활성 소속 직원 2명의 100.0%)이 상한 1명을 초과했습니다. "
                + "강제 실행하려면 실행 기록의 reactivationHold 를 확인한 뒤 force=true 와 maxReactivations(허용할 재활성 직원 수)로 재요청하세요");
        assertThat(writer.written).doesNotContain(막힌_가, 막힌_나);
        assertThat(writer.stored).containsExactly(김_백엔드);
        assertThat(state.users.get("blocked1").active()).isFalse();
        assertThat(state.users.get("blocked2").active()).isFalse();
        assertThat(snapshots.saved).hasSize(2);
        assertThat(snapshots.saved.get(1).inactive()).containsExactlyInAnyOrder(막힌_가, 막힌_나);
    }

    // 깨뜨릴 변경: 재적재가 비교 경로처럼 스냅샷이 없으면 SnapshotIntegrityException 으로 실패하기
    @Test
    @DisplayName("재적재는 직전 스냅샷이 없으면 판정하지 않고 끝낸다")
    void 재적재는_스냅샷이_없으면_판정하지_않는다() {
        // given — 스냅샷이 없다
        useCase = 유스케이스(상한_1명);
        source.willReturn(조직도(Set.of("kim", "blocked1", "blocked2"), Set.of()));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.reactivationHold()).isNull();
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 막힌_가, 막힌_나);
    }

    // 깨뜨릴 변경: 재적재가 깨짐이 아닌 스냅샷 읽기 오류도 삼키고 판정 없이 장부를 맞추기(설계 2026-11-25 E1)
    @Test
    @DisplayName("재적재는 깨짐이 아닌 오류로 직전 스냅샷을 읽지 못하면 FAILED 이고 아무것도 쓰지 않는다")
    void 재적재는_일시_오류로_스냅샷을_못_읽으면_실패한다() {
        // given
        useCase = 유스케이스(상한_1명);
        막힌_둘이_든_스냅샷을_둔다();
        snapshots.failFindLatest(new RuntimeException("DynamoDB 일시 장애(테스트)"));
        source.willReturn(조직도(Set.of("kim", "blocked1", "blocked2"), Set.of()));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("DynamoDB 일시 장애(테스트)");
        assertThat(writer.stored).containsExactly(김_백엔드);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.writing).isFalse();
        assertThat(state.users).isEmpty();
    }

    // 깨뜨릴 변경: 재적재가 깨진 스냅샷으로 회차를 실패시키기 — 재적재는 깨진 스냅샷을 고치는 통로다
    @Test
    @DisplayName("재적재는 직전 스냅샷이 깨져 있어도 판정 없이 끝낸다")
    void 재적재는_깨진_스냅샷이어도_판정_없이_끝낸다() {
        // given
        useCase = 유스케이스(상한_1명);
        막힌_둘이_든_스냅샷을_둔다();
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 묶음 수가 맞지 않습니다 — POST /admin/sync/rebuild 로 복구하세요"));
        source.willReturn(조직도(Set.of("kim", "blocked1", "blocked2"), Set.of()));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.reactivationHold()).isNull();
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 막힌_가, 막힌_나);
        assertThat(snapshots.saved).hasSize(2);
    }

    // 깨뜨릴 변경: 재활성 허용 수만 준 강제 재적재에서 maxDeletes 를 꺼내다 NPE, 또는 강제 재적재가 설정의 재활성 가드를 보기
    @Test
    @DisplayName("강제 재적재도 재활성 허용 수 안이면 재활성을 쓰고, 결론 앞에 재활성 허용 수를 적는다")
    void 강제_재적재는_재활성_허용_수_안이면_쓴다() {
        // given
        useCase = 유스케이스(상한_1명);
        막힌_둘이_든_스냅샷을_둔다();
        source.willReturn(조직도(Set.of("kim", "blocked1", "blocked2"), Set.of()));

        // when
        SyncRun run = 강제로_재적재한다(new ForceAllowance(null, 2));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.FORCED_REBUILD);
        assertThat(run.reactivationHold()).isNull();
        assertThat(run.message()).isEqualTo("강제 실행 — 재활성 허용 2명");
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 막힌_가, 막힌_나);
        assertThat(snapshots.saved.get(1).inactive()).isEmpty();
    }

    // 깨뜨릴 변경: 삭제 허용 수만 준 강제 재적재의 재활성 가드를 강제 가드(허용 수 무제한)나 꺼진 가드로 바꾸기 — 재활성_가드를_고른다 가 allowance != null 만 보기
    @Test
    @DisplayName("삭제만 강제로 넘긴 재적재의 재활성 가드는 설정대로다 — 재활성이 설정 상한을 넘으면 보류한다")
    void 삭제만_강제한_재적재도_재활성_가드는_설정대로_본다() {
        // given — 설정 상한 1명이면 보류할 재활성 둘. 운영자는 지울 줄만 허용했다
        useCase = 유스케이스(상한_1명);
        막힌_둘이_든_스냅샷을_둔다();
        source.willReturn(조직도(Set.of("kim", "blocked1", "blocked2"), Set.of()));

        // when
        SyncRun run = 강제로_재적재한다(new ForceAllowance(1000));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.FORCED_REBUILD);
        assertThat(run.reactivationHold()).isNotNull();
        assertThat(run.reactivationHold().count()).isEqualTo(2);
        assertThat(run.message()).startsWith("강제 실행 — 지울 줄 허용 1000건 / 재활성 가드 발동 — 직원 2명의 재활성을 보류(비활성 그대로 둠) / ");
        assertThat(writer.stored).doesNotContain(막힌_가, 막힌_나);
    }
}

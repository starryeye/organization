package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
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
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 재적재 — 장부를 버리지 않고 안에서 청소하고, 요청과 떼어 돈다 (설계 2026-09-29 §3·§4).
 */
class ScimRebuildUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");
    private static final RelationTuple 김_백엔드 = RelationTuple.directMember("kim", "DEV002");
    private static final RelationTuple 백엔드_개발본부 = RelationTuple.child("DEV002", "DEV001");
    private static final RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleScanner scanner;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        scanner = new FakeTupleScanner(writer);
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(NOW);
        lock = new FakeMutationLock();
        useCase = new ScimRebuildUseCase(state, writer, scanner, snapshots,
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id + "-이름", id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, code + "-조직", Set.of(members));
    }

    private void 조직도를_심는다() {
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", false)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
    }

    /** 재적재를 걸고 끝날 때까지 기다린다 — 재적재는 요청과 떼어 돈다(설계 §4). */
    private SyncRun 재적재한다(ScimRebuildMode mode) {
        SyncRun started = useCase.start(mode).block();
        return runs.awaitFinished(started.runId());
    }

    // ---------- 요청과 떼어 돈다 ----------

    @Test
    @DisplayName("start 는 락을 잡고 RUNNING 기록을 곧바로 주며, 재적재는 따로 끝까지 간다")
    void start는_RUNNING을_곧바로_준다() {
        // given — 쓰기를 늦춰 도는 중인 것을 볼 수 있게 한다
        조직도를_심는다();
        writer.delay = Duration.ofMillis(300);

        // when
        SyncRun started = useCase.start(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(started.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(started.source()).isEqualTo(SyncSource.SCIM);
        assertThat(started.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(runs.awaitFinished(started.runId()).status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    // ---------- tuples 모드 ----------

    @Test
    @DisplayName("tuples 모드는 현재상태가 요구하는 튜플을 전부 쓴다 — 비활성 직원은 빼고")
    void tuples_모드는_요구되는_튜플을_쓴다() {
        // given
        조직도를_심는다();

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.appliedDeltas.get(0).toWrite()).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
    }

    @Test
    @DisplayName("tuples 모드는 장부를 훑어 조직도가 요구하지 않는 줄만 지운다 — 스냅샷에 없던 찌꺼기도")
    void tuples_모드는_찌꺼기를_지운다() {
        // given — 장부에 누구도 기록하지 않은 줄이 있다
        조직도를_심는다();
        writer.stored.add(찌꺼기);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
        assertThat(writer.deleted).containsExactly(찌꺼기);
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(scanner.scanCount).hasValue(1);
    }

    @Test
    @DisplayName("tuples 모드는 장부를 비우지 않는다 — 쓰고 지우는 내내 기존 권한이 장부에 있다")
    void tuples_모드는_장부를_비우지_않는다() {
        // given — 장부에 이미 kim 의 권한이 있다
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));
        List<Boolean> 쓸때마다_있었나 = new CopyOnWriteArrayList<>();
        writer.onApply(() -> 쓸때마다_있었나.add(writer.stored.contains(김_백엔드)));

        // when
        재적재한다(ScimRebuildMode.TUPLES);

        // then — 전에는 장부를 지우고 다시 만들어, 그동안 모든 권한 질의가 false 였다
        assertThat(쓸때마다_있었나).isNotEmpty().doesNotContain(false);
        assertThat(writer.stored).contains(김_백엔드);
    }

    @Test
    @DisplayName("tuples 모드는 현재상태를 읽지 못하면 장부에 아무것도 쓰거나 지우지 않고 FAILED 다")
    void 읽기가_실패하면_장부를_건드리지_않는다() {
        // given — 비운 뒤에 읽기가 실패하면 전사 권한이 0 이 된다(점검 M13)
        조직도를_심는다();
        writer.stored.add(김_백엔드);
        state.failLoadAll(new IllegalStateException("DynamoDB 읽기 실패"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("DynamoDB 읽기 실패");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(scanner.scanCount).hasValue(0);
        assertThat(writer.stored).containsExactly(김_백엔드);
    }

    @Test
    @DisplayName("tuples 모드는 현재상태를 건드리지 않는다 — 상태가 곧 진실이다")
    void tuples_모드는_상태를_안_건드린다() {
        // given
        조직도를_심는다();

        // when
        재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(state.users).containsOnlyKeys("kim", "lee");
        assertThat(state.groups).containsOnlyKeys("DEV002", "DEV001");
    }

    @Test
    @DisplayName("tuples 모드는 SCIM 이력에 남고, 장부에 있다고 볼 줄을 스냅샷에 기록한다")
    void tuples_모드는_이력과_스냅샷을_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.source()).isEqualTo(SyncSource.SCIM);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.SCIM);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
    }

    @Test
    @DisplayName("조직도가 비었는데 장부에 줄이 있으면 지우지 않고 FAILED 다 — 조직도를 잃은 채로 돌면 장부 전체가 지워진다")
    void 빈_조직도면_장부를_지우지_않는다() {
        // given
        writer.stored.add(찌꺼기);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("조직도가 비어 있다 — 장부를 비우려면 mode=wipe");
        assertThat(writer.stored).containsExactly(찌꺼기);
        assertThat(snapshots.saved).isEmpty();
    }

    @Test
    @DisplayName("조직도도 장부도 비어 있으면 할 일 없이 정상 종료한다")
    void 둘_다_비었으면_정상_종료한다() {
        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("쓰기가 일부 실패하면 PARTIAL 이고 스냅샷에는 실제로 쓴 것만 담긴다")
    void 쓰기_부분_실패는_PARTIAL이다() {
        // given
        조직도를_심는다();
        writer.failFor(백엔드_개발본부::equals);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(김_백엔드);
    }

    @Test
    @DisplayName("지우지 못한 찌꺼기는 스냅샷에 남는다 — 장부에 아직 있다")
    void 지우지_못한_줄은_스냅샷에_남는다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);
        writer.failFor(찌꺼기::equals);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부, 찌꺼기);
    }

    @Test
    @DisplayName("tuples 모드에서 지우기가 연속 실패 차단기로 멈추면 FAILED 지만 스냅샷은 남는다 — 쓴 줄과 지우지 못한 줄을 담는다")
    void 지우기가_차단기로_멈춰도_스냅샷을_남긴다() {
        // given — 찌꺼기 지우기가 실패한 채 멈춘다
        조직도를_심는다();
        writer.stored.add(찌꺼기);
        writer.failFor(찌꺼기::equals);
        writer.abortWhen(delta -> !delta.toDelete().isEmpty());

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("연속 실패로 멈췄다");
        assertThat(run.snapshotId()).isEqualTo(snapshots.saved.get(0).id());
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부, 찌꺼기);
    }

    @Test
    @DisplayName("tuples 모드에서 쓰기가 연속 실패 차단기로 멈추면 장부를 훑지 않고 스냅샷도 만들지 않은 채 FAILED 다")
    void 쓰기가_차단기로_멈추면_훑지_않는다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);
        writer.abortWhen(delta -> !delta.toWrite().isEmpty());

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 장부를 훑기 전이라 무엇을 지울지 모른다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("연속 실패로 멈췄다");
        assertThat(scanner.scanCount).hasValue(0);
        assertThat(snapshots.saved).isEmpty();
        assertThat(lock.released).hasValue(1);
    }

    // ---------- 보류 목록 (설계 2026-10-03 §4.6) ----------

    @Test
    @DisplayName("재적재는 순환으로 버린 연결로 보류 목록을 다시 쓴다 — 묵은 줄은 사라진다")
    void 재적재가_보류_목록을_다시_쓴다() {
        // given — A ⊃ B, B ⊃ A. 보류 목록에는 묵은 줄이 있다
        state.saveGroup(조직("A", MemberRef.group("B"))).block();
        state.saveGroup(조직("B", MemberRef.group("A"))).block();
        state.cutEdges.add(new GroupEdge("OLD", "X"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 사전순 DFS 가 B ⊃ A 를 버린다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.cutEdges).containsExactly(new GroupEdge("B", "A"));
    }

    @Test
    @DisplayName("조직도에 순환이 없으면 재적재가 보류 목록을 비운다")
    void 순환이_없으면_보류_목록을_비운다() {
        // given
        조직도를_심는다();
        state.cutEdges.add(new GroupEdge("OLD", "X"));

        // when
        재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("조직도가 비어 재적재가 멈추면 보류 목록을 건드리지 않는다")
    void 빈_조직도로_멈추면_보류_목록을_두고_간다() {
        // given — 조직도는 비었는데 장부에 줄이 있다(②-2 의 빈 조직도 가드)
        writer.stored.add(김_백엔드);
        state.cutEdges.add(new GroupEdge("A", "B"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(state.cutEdges).containsExactly(new GroupEdge("A", "B"));
    }

    // ---------- wipe 모드 ----------

    @Test
    @DisplayName("wipe 모드는 장부를 비운 뒤 현재상태의 직원·조직을 전부 지운다")
    void wipe_모드는_장부와_상태를_비운다() {
        // given
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).isEmpty();
        assertThat(state.users).isEmpty();
        assertThat(state.groups).isEmpty();
    }

    @Test
    @DisplayName("wipe 는 보류 목록도 비운다")
    void wipe는_보류_목록을_비운다() {
        // given
        조직도를_심는다();
        state.cutEdges.add(new GroupEdge("DEV001", "DEV002"));

        // when
        재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("wipe 모드는 튜플을 하나도 쓰지 않는다 — 상태가 비니 요구되는 튜플도 없다")
    void wipe_모드는_튜플을_안_쓴다() {
        // given
        조직도를_심는다();
        writer.stored.add(김_백엔드);

        // when
        재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(writer.appliedDeltas).isNotEmpty()
                .allSatisfy(delta -> assertThat(delta.toWrite()).isEmpty());
    }

    @Test
    @DisplayName("wipe 모드는 장부에서 한 줄이라도 못 지우면 조직도를 건드리지 않고 FAILED 다 — 다시 실행할 수 있게")
    void wipe_모드는_청소가_실패하면_조직도를_지킨다() {
        // given — 순서가 뒤집혀 있으면 조직도가 사라지고 낡은 권한만 남는다
        조직도를_심는다();
        writer.stored.add(김_백엔드);
        writer.failFor(김_백엔드::equals);

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("지우지 못해");
        assertThat(state.users).containsOnlyKeys("kim", "lee");
        assertThat(state.groups).containsOnlyKeys("DEV002", "DEV001");
    }

    @Test
    @DisplayName("wipe 모드는 지우기가 연속 실패 차단기로 멈추면 조직도를 건드리지 않고, 멈춘 이유를 함께 남긴다")
    void wipe_모드는_차단기로_멈추면_조직도를_지킨다() {
        // given
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));
        writer.failFor(찌꺼기::equals);
        writer.abortWhen(delta -> !delta.toDelete().isEmpty());

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("지우지 못해").contains("연속 실패로 멈췄다");
        assertThat(state.users).containsOnlyKeys("kim", "lee");
        assertThat(state.groups).containsOnlyKeys("DEV002", "DEV001");
    }

    @Test
    @DisplayName("wipe 모드는 장부를 훑지 못하면 조직도를 건드리지 않고 FAILED 다")
    void wipe_모드는_훑기가_실패하면_조직도를_지킨다() {
        // given
        조직도를_심는다();
        scanner.failWith(new IllegalStateException("OpenFGA 접속 불가"));

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(state.users).containsOnlyKeys("kim", "lee");
    }

    @Test
    @DisplayName("wipe 모드는 RESET 트리거로 이력에 남고, 감사 이력은 지우지 않는다")
    void wipe_모드는_감사_이력을_남긴다() {
        // given — 지우기 전에 이미 쌓여 있던 스냅샷이 있다
        조직도를_심는다();
        재적재한다(ScimRebuildMode.TUPLES);
        int 지우기_전_스냅샷 = snapshots.saved.size();

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then — 사고 뒤에 무슨 일이 있었는지 볼 유일한 기록이라 남긴다
        assertThat(run.trigger()).isEqualTo(SyncTrigger.RESET);
        assertThat(snapshots.saved).hasSize(지우기_전_스냅샷);
        assertThat(runs.finished).hasSize(2);
    }

    // ---------- 락 ----------
    // 락 획득·반납·거절의 상세 동작은 ScimRebuildLockTest 가 본다. 여기서는 정상 경로와 실패 경로의 반납만 회귀로 남긴다.

    @Test
    @DisplayName("재적재가 도는 동안, 즉 쓰기가 실제로 일어나는 순간에도 락이 잡혀 있다")
    void 도는_동안_락이_잡혀있다() {
        // given — 쓰기가 실제로 실행되는 순간의 락 상태를 남긴다.
        // 훅 안에서 바로 assertThat 을 부르면 안 된다 — 훅에서 던진 AssertionError 는 작업 실패(FAILED)로 삼켜진다.
        조직도를_심는다();
        AtomicInteger acquiredDuringWrite = new AtomicInteger(-1);
        AtomicInteger releasedDuringWrite = new AtomicInteger(-1);
        writer.onApply(() -> {
            acquiredDuringWrite.set(lock.acquired.get());
            releasedDuringWrite.set(lock.released.get());
        });

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 쓰기 시점에는 락이 잡혀 있었고(반납 전), 끝나면 반납된다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(acquiredDuringWrite).hasValue(1);
        assertThat(releasedDuringWrite).hasValue(0);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("재적재가 실패해도 락은 반납된다")
    void 실패해도_락을_반납한다() {
        // given
        조직도를_심는다();
        scanner.failWith(new IllegalStateException("터짐"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 안 반납하면 이후 모든 SCIM 변경이 영구히 503 이 된다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(lock.released).hasValue(1);
    }

    // ---------- 반납 뒤 저장 (설계 2026-10-02 §5, 점검 P8) ----------

    @Test
    @DisplayName("튜플 스냅샷은 락을 반납한 뒤에 저장한다 — 저장하는 동안 SCIM 쓰기를 막지 않는다(점검 P8)")
    void 스냅샷은_락을_반납한_뒤_저장한다() {
        // given
        AtomicReference<Boolean> 저장할때_락 = new AtomicReference<>();
        FakeSnapshotRepository 지켜보는_저장소 = new FakeSnapshotRepository() {
            @Override
            public Mono<Void> save(TupleSnapshot snapshot) {
                return Mono.defer(() -> {
                    저장할때_락.set(lock.isHeld());
                    return super.save(snapshot);
                });
            }
        };
        var 재적재 = new ScimRebuildUseCase(state, writer, scanner, 지켜보는_저장소,
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        // when
        var run = runs.awaitFinished(재적재.start(ScimRebuildMode.TUPLES).block().runId());

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(저장할때_락.get()).as("저장할 때 락은 이미 풀려 있다").isFalse();
        assertThat(지켜보는_저장소.saved).hasSize(1);
    }

    @Test
    @DisplayName("반납 뒤 스냅샷 저장이 실패하면 FAILED 이고 사유에 장부는 맞췄다고 남긴다")
    void 반납_뒤_저장_실패는_FAILED() {
        // given
        snapshots.failSave(new IllegalStateException("스로틀"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).startsWith("장부는 맞췄다 — 스냅샷 저장 실패: ").contains("스로틀");
        assertThat(lock.released).hasValue(1);
    }
}

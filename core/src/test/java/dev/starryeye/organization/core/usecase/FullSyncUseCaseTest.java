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
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FullSyncUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-14T03:00:00Z");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleScanner scanner;
    private FakeSyncRunRepository runs;
    private FullSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(고정시각);
        scanner = new FakeTupleScanner(writer);
        useCase = new FullSyncUseCase(source, snapshots, state, writer, scanner,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
                new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", true);
    }

    private static DirectorySnapshot 조직도(Set<String> userIds, String groupCode) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        userIds.forEach(id -> users.put(id, 직원(id)));
        Set<MemberRef> members = userIds.stream().map(MemberRef::user).collect(Collectors.toSet());
        return new DirectorySnapshot(users,
                Map.of(groupCode, new DirectoryGroup(groupCode, "cn=" + groupCode, "백엔드팀", members)));
    }

    private static Set<RelationTuple> 소속튜플(int count, String groupCode) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember("user" + i, groupCode))
                .collect(Collectors.toSet());
    }

    /** 동기화를 걸고 끝날 때까지 기다린다 — 동기화는 요청과 떼어 돈다(설계 2026-09-29 §4). */
    private SyncRun 동기화한다(SyncTrigger trigger) {
        SyncRun started = useCase.start(trigger, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("최초 동기화는 읽어온 전체를 생성 대상으로 삼아 OpenFGA에 반영한다")
    void 최초_동기화는_전체를_생성한다() {
        // given
        source.willReturn(조직도(Set.of("kim", "lee"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.writtenCount()).isEqualTo(2);
        assertThat(run.deletedCount()).isZero();
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toWrite()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("lee", "DEV002"));
    }

    @Test
    @DisplayName("동기화가 성공하면 새 스냅샷과 현재상태가 모두 저장된다")
    void 성공하면_스냅샷과_현재상태가_저장된다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).id()).isEqualTo("20260814T030000000-LDAP");
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.LDAP);
        assertThat(run.snapshotId()).isEqualTo("20260814T030000000-LDAP");
        assertThat(state.users).containsKey("kim");
        assertThat(state.groups).containsKey("DEV002");
    }

    @Test
    @DisplayName("변경이 없으면 OpenFGA를 호출하지 않고 새 스냅샷도 만들지 않는다")
    void 변경이_없으면_아무것도_쓰지_않는다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("kim", "DEV002")))).block();
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("변경 없음");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.saved).hasSize(1);
    }

    @Test
    @DisplayName("일부 튜플 적용에 실패하면 성공분만 새 스냅샷에 담겨 다음 동기화가 실패분을 다시 잡는다")
    void 부분_실패시_성공분만_스냅샷에_담긴다() {
        // given — 기존 스냅샷 없음, 목표 2건 중 lee 만 실패
        source.willReturn(조직도(Set.of("kim", "lee"), "DEV002"));
        writer.failFor(tuple -> tuple.user().equals("user:lee"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(run.failureCount()).isEqualTo(1);
        assertThat(snapshots.saved.get(0).tuples())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("삭제 가드가 발동하면 OpenFGA를 건드리지 않고 사유와 함께 중단한다")
    void 삭제_가드가_발동하면_중단한다() {
        // given — 기준 20건, LDAP 이 0건을 반환해 전건 삭제 상황
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 소속튜플(20, "DEV002"))).block();
        source.willReturn(DirectorySnapshot.empty());

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("임계치");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.saved).hasSize(1);
    }

    @Test
    @DisplayName("FORCED 트리거는 삭제 가드를 건너뛰고 전건 삭제를 진행한다")
    void 강제_실행은_가드를_건너뛴다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 소속튜플(20, "DEV002"))).block();
        source.willReturn(DirectorySnapshot.empty());

        // when
        var run = 동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.deletedCount()).isEqualTo(20);
        assertThat(snapshots.saved).hasSize(2);
        assertThat(snapshots.saved.get(1).tuples()).isEmpty();
    }

    @Test
    @DisplayName("LDAP 조회가 실패하면 FAILED로 기록하고 스냅샷과 현재상태를 건드리지 않는다")
    void 소스_실패는_FAILED로_기록된다() {
        // given
        source.willFail(new IllegalStateException("LDAP 연결 실패"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("LDAP 연결 실패");
        assertThat(snapshots.saved).isEmpty();
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("새 스냅샷은 직전 스냅샷에서 삭제 성공분을 빼고 생성 성공분을 더한 결과가 된다")
    void 새_스냅샷은_직전_스냅샷_기준으로_계산된다() {
        // given — 직전 kim, lee / 목표 kim, park
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("kim", "DEV002"),
                       RelationTuple.directMember("lee", "DEV002")))).block();
        source.willReturn(조직도(Set.of("kim", "park"), "DEV002"));

        // when
        동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(snapshots.saved.get(1).tuples()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("park", "DEV002"));
    }

    @Test
    @DisplayName("쓰기가 연속 실패 차단기로 멈춰도 이미 나간 쓰기로 스냅샷과 현재상태를 남기고 FAILED 다 — 기준선이 장부를 따라간다")
    void 차단기로_멈춰도_나간_쓰기를_기록한다() {
        // given — 직전 kim, lee / 목표 kim, park, choi. lee 지우기와 park 쓰기는 나갔고 choi 는 실패한 채 멈춘다
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("kim", "DEV002"),
                       RelationTuple.directMember("lee", "DEV002")))).block();
        source.willReturn(조직도(Set.of("kim", "park", "choi"), "DEV002"));
        writer.failFor(tuple -> tuple.user().equals("user:choi"));
        writer.abortWhen(delta -> true);

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 멈췄다는 사실은 FAILED 로, 건수는 실제로 나간 것으로 남는다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("연속 실패로 멈췄다");
        assertThat(run.writtenCount()).isEqualTo(1);
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(run.failureCount()).isEqualTo(1);

        // then — 새 스냅샷 = 직전 − 지운 것 + 쓴 것. 저장하지 않으면 다음 회차가 이미 지운 lee 를 "있다"고 믿는다
        assertThat(snapshots.saved).hasSize(2);
        assertThat(run.snapshotId()).isEqualTo(snapshots.saved.get(1).id());
        assertThat(snapshots.saved.get(1).tuples()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("park", "DEV002"));
        assertThat(state.users).containsOnlyKeys("kim", "park", "choi");
        assertThat(snapshots.writing).as("스냅샷을 남겼으니 표시가 사라진다").isFalse();
    }

    @Test
    @DisplayName("기준선 스냅샷이 깨져 있으면 아무것도 쓰지 않고 FAILED 로 끝나며, 이유에 복구 방법이 남는다")
    void 기준선이_깨지면_쓰지_않고_FAILED() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 메타가 없습니다 — POST /admin/sync/rebuild 로 복구하세요"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("POST /admin/sync/rebuild");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.saved).isEmpty();
        assertThat(state.users).isEmpty();
    }

    // ---------- 기록 중 표시와 훑어 맞추기 (설계 2026-09-30 §4) ----------

    @Test
    @DisplayName("OpenFGA 에 쓰기 직전에 '기록 중' 표시를 남기고, 스냅샷을 저장하면 표시가 사라진다")
    void 쓰기_직전에_표시를_남기고_저장하면_사라진다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        AtomicBoolean 쓸때_표시 = new AtomicBoolean();
        writer.onApply(() -> 쓸때_표시.set(snapshots.writing.get()));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(쓸때_표시).as("표시가 쓰기보다 먼저다").isTrue();
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("스냅샷 저장이 실패하면 '기록 중' 표시가 남는다 — 다음 회차가 이것을 보고 장부를 훑어 맞춘다")
    void 저장이_실패하면_표시가_남는다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패(스로틀)"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.writing).isTrue();
    }

    @Test
    @DisplayName("쓰는 도중 멈추면(기한·서버 종료·오류) '기록 중' 표시가 남는다 — 표시를 지우는 것은 스냅샷 저장뿐이다")
    void 쓰는_도중_멈추면_표시가_남는다() {
        // given — 기한·종료는 작업을 취소하고, 오류는 작업을 끝낸다. 어느 쪽이든 스냅샷 저장에 닿지 않는다
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.onApply(() -> {
            throw new IllegalStateException("쓰는 도중 멈춤(흉내)");
        });

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.saved).isEmpty();
        assertThat(snapshots.writing).isTrue();
    }

    @Test
    @DisplayName("변경이 없으면 '기록 중' 표시를 남기지 않는다")
    void 변경이_없으면_표시하지_않는다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("kim", "DEV002")))).block();
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("'기록 중' 표시를 남기지 못하면 OpenFGA 에 쓰지 않고 FAILED 다 — 표시 없이 쓰기 시작하는 일은 없다")
    void 표시를_못_남기면_쓰지_않는다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failMarkWriting(new IllegalStateException("포인터 쓰기 실패"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("지난 회차가 기록 전에 멈췄으면 장부를 훑어 맞춘다 — 되돌려진 입사자의 권한이 남지 않는다(점검 M2)")
    void 멈춘_뒤_첫_동기화는_훑어_맞춘다_남는_쪽() {
        // given — 월요일 기준선엔 park 이 없다. 화요일 회차가 park 의 권한을 쓰고 스냅샷 저장 전에 멈췄다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        snapshots.save(new TupleSnapshot("월요일", 고정시각, SyncSource.LDAP, Set.of(김))).block();
        writer.stored.addAll(Set.of(김, 박));
        snapshots.writing.set(true);
        // 수요일 park 입사 취소 — 목요일 LDAP 에는 kim 만 있다
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 기준선(월요일)과 비교하면 지울 게 없지만, 장부를 훑어 park 을 찾아 지운다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).contains("기준선 의심");
        assertThat(writer.stored).containsExactly(김);
        assertThat(snapshots.writing).isFalse();
        assertThat(snapshots.saved.get(snapshots.saved.size() - 1).tuples()).containsExactly(김);
    }

    @Test
    @DisplayName("지운 뒤 멈췄다가 되살아난 직원도 권한을 되찾는다(점검 M2 의 빠지는 쪽)")
    void 멈춘_뒤_첫_동기화는_훑어_맞춘다_빠지는_쪽() {
        // given — 월요일 기준선엔 kim 이 있다. 화요일 회차가 kim 의 권한을 지우고 스냅샷 저장 전에 멈췄다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        snapshots.save(new TupleSnapshot("월요일", 고정시각, SyncSource.LDAP, Set.of(김))).block();
        snapshots.writing.set(true);
        // 오후에 kim 이 되살아났다
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 기준선과 비교하면 쓸 게 없지만, 훑어 맞추기는 있어야 할 줄을 전부 쓴다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).containsExactly(김);
    }

    @Test
    @DisplayName("훑어 맞추기도 삭제 가드를 지킨다 — 지울 줄이 장부의 30% 를 넘으면 지우지 않고 ABORTED, 표시는 남는다")
    void 훑어_맞추기도_가드를_지킨다() {
        // given — 장부에 20줄, LDAP 에는 그중 10명만 남았다(50% 를 지워야 한다)
        writer.stored.addAll(소속튜플(20, "DEV002"));
        snapshots.writing.set(true);
        source.willReturn(조직도(IntStream.range(0, 10).mapToObj(i -> "user" + i).collect(Collectors.toSet()), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("기준선 의심").contains("임계치");
        assertThat(writer.stored).hasSize(20);
        assertThat(snapshots.writing).isTrue();
    }

    @Test
    @DisplayName("훑어 맞추기가 스냅샷 저장 실패로 멈추면 메시지에 기준선 의심 사유가 남는다")
    void 훑어_맞추기가_저장_실패로_멈추면_기준선_의심이_메시지에_남는다() {
        // given
        snapshots.writing.set(true);
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 실패 사유만 남기면 지난 회차가 기록 전에 멈췄다는 사정이 사라진다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("기준선 의심").contains("스냅샷 저장 실패");
    }

    @Test
    @DisplayName("훑어 맞추기에서 이미 있던 줄의 쓰기가 실패해도 스냅샷에 남는다")
    void 훑어_맞추기에서_이미_있던_줄의_쓰기가_실패해도_스냅샷에_남는다() {
        // given — 장부에 kim, park 이 이미 있고 park 의 다시 쓰기가 실패한다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        writer.stored.addAll(Set.of(김, 박));
        snapshots.writing.set(true);
        source.willReturn(조직도(Set.of("kim", "park"), "DEV002"));
        writer.failFor(tuple -> tuple.equals(박));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 박의 다시 쓰기는 실패했지만 장부에는 있었으니 스냅샷에 남는다
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(snapshots.saved.size() - 1).tuples()).containsExactlyInAnyOrder(김, 박);
        assertThat(snapshots.writing).isFalse();

        // when — 다음 동기화에서 박이 더는 LDAP 에 없다
        writer.failFor(tuple -> false);
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 박의 줄이 이번에 지워진다
        assertThat(run2.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).containsExactly(김);
    }

    @Test
    @DisplayName("force=true(FORCED) 면 훑어 맞추기의 가드를 건너뛰고 지운다")
    void 강제면_훑어_맞추기가_지운다() {
        // given
        writer.stored.addAll(소속튜플(20, "DEV002"));
        snapshots.writing.set(true);
        source.willReturn(조직도(IntStream.range(0, 10).mapToObj(i -> "user" + i).collect(Collectors.toSet()), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).hasSize(10);
        assertThat(snapshots.writing).isFalse();
    }
}

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
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
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
        useCase = 유스케이스();
    }

    private FullSyncUseCase 유스케이스() {
        return new FullSyncUseCase(source, snapshots, state, writer, scanner,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
                new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", true);
    }

    private static DirectorySnapshot 조직도(Set<String> userIds, String groupCode) {
        return 조직도(userIds, Set.of(), groupCode);
    }

    /** 활성 직원과 막힌(비활성) 직원이 한 조직에 함께 있는 조직도 — 막힌 직원도 그림과 소속에 그대로 있다(설계 2026-10-20 §5.1). */
    private static DirectorySnapshot 조직도(Set<String> 활성, Set<String> 비활성, String groupCode) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        활성.forEach(id -> users.put(id, 직원(id)));
        비활성.forEach(id -> users.put(id, new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", false)));
        Set<MemberRef> members = users.keySet().stream().map(MemberRef::user).collect(Collectors.toSet());
        return new DirectorySnapshot(users,
                Map.of(groupCode, new DirectoryGroup(groupCode, "cn=" + groupCode, "백엔드팀", members)));
    }

    /** user{from} ~ user{to - 1} */
    private static Set<String> 직원들(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "user" + i).collect(Collectors.toSet());
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
        // given — 포인터는 있는데 스냅샷이 깨졌다(포인터가 없으면 장부를 훑어 맞추므로 미리 저장해 포인터를 둔다)
        빈_기준선을_둔다();
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 메타가 없습니다 — POST /admin/sync/rebuild 로 복구하세요"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("POST /admin/sync/rebuild");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.saved).as("미리 저장한 것뿐이다").hasSize(1);
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("포인터는 기준선이 있다고 하는데 스냅샷이 읽히지 않으면 빈 기준선과 비교하지 않고 FAILED 다 — 장부의 줄을 영원히 못 지우는 뒷문을 닫는다")
    void 기준선이_있다는데_스냅샷이_없으면_빈_기준선과_비교하지_않는다() {
        // given — 포인터 상태는 "있음"인데 최신 스냅샷이 비어 돌아온다
        snapshots = new FakeSnapshotRepository() {
            @Override
            public Mono<BaselineState> baselineState() {
                return Mono.just(BaselineState.PRESENT);
            }
        };
        useCase = 유스케이스();
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

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

    // ---------- 기준선 없음 (설계 2026-10-20 §4) ----------

    @Test
    @DisplayName("기준선이 없으면(포인터 없음) 장부를 훑어 목표 밖의 줄을 지운다 — 새 테이블과 기존 store 짝에서 찌꺼기가 영원히 남지 않는다")
    void 기준선이_없으면_장부를_훑어_목표_밖의_줄을_지운다() {
        // given — 저장한 스냅샷이 없는데 장부에는 목표 밖의 ghost 가 이미 있다(테이블을 다시 만든 경우)
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 유령 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.add(유령);
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 빈 기준선과 비교하면 지울 게 없지만, 장부를 훑어 ghost 를 찾아 지운다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).contains("기준선 없음");
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(writer.stored).containsExactly(김);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(김);
        assertThat(snapshots.saved.get(0).uncertain()).isEmpty();
        assertThat(snapshots.writing).as("스냅샷을 남겼으니 표시가 사라진다").isFalse();
    }

    @Test
    @DisplayName("기준선이 없는 첫 설치(빈 장부)는 목표를 모두 쓰고 결과가 전과 같다")
    void 기준선이_없는_첫_설치는_목표를_모두_쓰고_결과가_지금과_같다() {
        // given — 빈 장부
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        source.willReturn(조직도(Set.of("kim", "lee"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.writtenCount()).isEqualTo(2);
        assertThat(run.deletedCount()).isZero();
        assertThat(writer.stored).containsExactlyInAnyOrder(김, 이);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김, 이);
        assertThat(state.users).containsOnlyKeys("kim", "lee");
    }

    @Test
    @DisplayName("기준선이 없어도 삭제 가드는 훑은 장부로 본다 — 장부의 대부분이 목표 밖이면 지우지 않고 ABORTED")
    void 기준선이_없어도_삭제_가드는_훑은_장부로_본다() {
        // given — 장부에 20줄, 목표는 그중 10명뿐이다(50% 를 지워야 한다). 저장한 스냅샷은 없다
        writer.stored.addAll(소속튜플(20, "DEV002"));
        source.willReturn(조직도(IntStream.range(0, 10).mapToObj(i -> "user" + i).collect(Collectors.toSet()), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("기준선 없음").contains("훑은 장부").contains("임계치");
        assertThat(writer.stored).as("지우지 않았다").hasSize(20);
        assertThat(writer.deleted).isEmpty();
    }

    @Test
    @DisplayName("기준선이 없는 첫 회차가 가드에 통째로 걸리면 스냅샷이 없으므로 다음 회차도 장부를 다시 훑는다")
    void 기준선이_없는_첫_회차가_가드에_통째로_걸리면_다음_회차도_장부를_훑는다() {
        // given — 장부에 20줄, 목표는 그중 10명뿐이라 가드가 걸린다
        writer.stored.addAll(소속튜플(20, "DEV002"));
        source.willReturn(조직도(IntStream.range(0, 10).mapToObj(i -> "user" + i).collect(Collectors.toSet()), "DEV002"));
        동기화한다(SyncTrigger.SCHEDULED);
        int 첫_회차까지_훑은_수 = scanner.scanCount.get();

        // when — 2회차도 같은 상황이다
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 첫 회차가 스냅샷을 남기지 못했으니 기준선은 여전히 없다. 빈 기준선과 비교하지 않고 다시 훑는다
        assertThat(첫_회차까지_훑은_수).isEqualTo(1);
        assertThat(scanner.scanCount.get()).isEqualTo(첫_회차까지_훑은_수 + 1);
        assertThat(run2.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(snapshots.saved).isEmpty();
        assertThat(snapshots.writing).as("표시가 남아 다음 회차도 기준선을 믿지 않는다").isTrue();
        assertThat(writer.stored).hasSize(20);
    }

    // ---------- 결과를 모르는 줄 (설계 2026-10-20 §3) ----------

    /**
     * 비교 경로로 가려고 빈 기준선을 미리 저장한다. 저장한 스냅샷이 없으면 장부를 훑어 맞추고(설계 2026-10-20 §4), 훑기가 결과를 모르는 쓰기의
     * 실제를 알려 줘서 불확실 줄이 생기지 않는다 — 이 절의 시험은 비교 경로의 식(설계 §3.3)을 본다.
     */
    private void 빈_기준선을_둔다() {
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, Set.of())).block();
    }

    /** 저장한 스냅샷의 확실한 줄(B)과 불확실 줄(U)은 겹치지 않는다(설계 2026-10-20 §3.2). */
    private static void 겹치지_않는다(TupleSnapshot snapshot) {
        assertThat(snapshot.tuples()).doesNotContainAnyElementsOf(snapshot.uncertain());
    }

    @Test
    @DisplayName("결과를 모르는 쓰기는 불확실 줄로 남고, 다음 회차에 목표에서 빠졌으면 지운다 — 응답만 잃은 권한이 영원히 남지 않는다")
    void 결과를_모르는_쓰기는_불확실_줄로_남고_목표에서_빠지면_다음_회차가_지운다() {
        // given — 서버는 kim 의 쓰기를 반영했는데 응답을 잃었다
        빈_기준선을_둔다();
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.lostResponseFor(tuple -> tuple.equals(김));

        // when
        var run1 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 반영됐는지 모르므로 확실한 줄(tuples)이 아니라 불확실 줄에 적힌다
        assertThat(run1.status()).isEqualTo(SyncStatus.PARTIAL);
        TupleSnapshot 첫째 = snapshots.saved.get(1);
        assertThat(첫째.uncertain()).containsExactly(김);
        assertThat(첫째.tuples()).isEmpty();
        겹치지_않는다(첫째);
        assertThat(writer.stored).as("실제로는 반영돼 있다").containsExactly(김);

        // given — 그 사이 kim 이 조직에서 빠졌다
        writer.lostResponseFor(tuple -> false);
        source.willReturn(조직도(Set.of(), "DEV002"));

        // when
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 불확실 줄이 목표에 없으니 지운다. 장부에서 사라지고 불확실 줄도 비워진다
        assertThat(run2.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.appliedDeltas.get(writer.appliedDeltas.size() - 1).toDelete()).containsExactly(김);
        assertThat(writer.stored).isEmpty();
        TupleSnapshot 둘째 = snapshots.saved.get(2);
        assertThat(둘째.uncertain()).isEmpty();
        assertThat(둘째.tuples()).isEmpty();
    }

    @Test
    @DisplayName("결과를 모르는 지우기는 불확실 줄로 남고, 다음 회차에 목표에 다시 들었으면 쓴다 — 응답만 잃은 지우기가 권한을 영원히 빼지 않는다")
    void 결과를_모르는_지우기는_불확실_줄로_남고_목표에_다시_들면_다음_회차가_쓴다() {
        // given — 기준선에 kim, lee. 이번 목표는 kim 뿐이라 lee 를 지우는데, 서버가 반영하고 응답을 잃는다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, Set.of(김, 이))).block();
        writer.stored.addAll(Set.of(김, 이));
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.lostResponseFor(tuple -> tuple.equals(이));

        // when
        var run1 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — lee 는 확실한 줄에서 빠지고 불확실 줄로 옮겨 간다
        assertThat(run1.status()).isEqualTo(SyncStatus.PARTIAL);
        TupleSnapshot 첫째 = snapshots.saved.get(1);
        assertThat(첫째.tuples()).containsExactly(김);
        assertThat(첫째.uncertain()).containsExactly(이);
        겹치지_않는다(첫째);

        // given — lee 가 조직에 다시 들었다
        writer.lostResponseFor(tuple -> false);
        source.willReturn(조직도(Set.of("kim", "lee"), "DEV002"));

        // when
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 장부에 lee 가 다시 생기고 기준선도 둘 다 담는다
        assertThat(run2.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).containsExactlyInAnyOrder(김, 이);
        TupleSnapshot 둘째 = snapshots.saved.get(2);
        assertThat(둘째.tuples()).containsExactlyInAnyOrder(김, 이);
        assertThat(둘째.uncertain()).isEmpty();
    }

    @Test
    @DisplayName("쓰기가 연속 실패 차단기로 멈춘 회차도 결과를 모르는 줄을 불확실 줄로 남긴다")
    void 차단기로_멈춘_회차도_불확실_줄을_남긴다() {
        // given — kim 의 쓰기는 응답을 잃고, 같은 델타가 차단기로 멈춘다
        빈_기준선을_둔다();
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.lostResponseFor(tuple -> tuple.equals(김));
        writer.abortWhen(delta -> true);

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.saved).hasSize(2);
        assertThat(snapshots.saved.get(1).uncertain()).containsExactly(김);
        assertThat(snapshots.saved.get(1).tuples()).isEmpty();
        겹치지_않는다(snapshots.saved.get(1));
        assertThat(snapshots.writing).as("스냅샷을 남겼으니 표시가 사라진다").isFalse();
    }

    @Test
    @DisplayName("불확실 줄이 남아 있으면 목표가 지난 회차와 같아도 변경 없음이 아니다 — 목표에 없는 불확실 줄은 다시 지운다")
    void 불확실_줄이_남아_있으면_목표가_같아도_변경_없음이_아니다() {
        // given — 1회차: 기준선의 lee 를 지웠는데 응답을 잃었다. 목표는 kim 뿐이다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, Set.of(김, 이))).block();
        writer.stored.addAll(Set.of(김, 이));
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.lostResponseFor(tuple -> tuple.equals(이));
        동기화한다(SyncTrigger.SCHEDULED);
        int 첫_회차까지 = writer.appliedDeltas.size();

        // when — 2회차의 목표는 1회차와 같다
        writer.lostResponseFor(tuple -> false);
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 기준선(kim)과 목표(kim)는 같지만 lee 를 다시 보낸다. 겹치는 줄은 없다
        assertThat(run2.message()).isNotEqualTo("변경 없음");
        assertThat(writer.appliedDeltas).hasSize(첫_회차까지 + 1);
        var 마지막 = writer.appliedDeltas.get(writer.appliedDeltas.size() - 1);
        assertThat(마지막.toDelete()).containsExactly(이);
        assertThat(마지막.toWrite()).isEmpty();
        assertThat(snapshots.saved.get(2).uncertain()).isEmpty();
        assertThat(snapshots.saved.get(2).tuples()).containsExactly(김);
    }

    @Test
    @DisplayName("불확실 줄을 다시 보냈는데 확정적으로 실패하면(거절·보내지 않음) 불확실 줄로 남는다 — 반영 여부를 아직 모른다")
    void 확정_실패한_불확실_줄은_불확실_줄로_남는다() {
        // given — 1회차: kim 의 쓰기가 응답을 잃어 불확실 줄이 됐다
        빈_기준선을_둔다();
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.lostResponseFor(tuple -> tuple.equals(김));
        동기화한다(SyncTrigger.SCHEDULED);

        // when — 2회차: kim 을 다시 쓰는데 이번에는 거절된다. 반영 여부는 여전히 모른다
        writer.lostResponseFor(tuple -> false);
        writer.failFor(tuple -> tuple.equals(김));
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 확실한 줄로 옮기지도, 불확실 줄에서 지우지도 않는다
        assertThat(run2.status()).isEqualTo(SyncStatus.PARTIAL);
        TupleSnapshot 둘째 = snapshots.saved.get(2);
        assertThat(둘째.uncertain()).containsExactly(김);
        assertThat(둘째.tuples()).isEmpty();
        겹치지_않는다(둘째);

        // when — 3회차: kim 이 조직에서 빠졌다
        writer.failFor(tuple -> false);
        source.willReturn(조직도(Set.of(), "DEV002"));
        동기화한다(SyncTrigger.SCHEDULED);

        // then — 불확실 줄로 남아 있었기에 이번에 지운다
        assertThat(writer.stored).isEmpty();
        assertThat(snapshots.saved.get(3).uncertain()).isEmpty();
    }

    @Test
    @DisplayName("삭제 가드의 기준 수는 확실한 줄과 불확실 줄을 합친 수다 — 불확실 줄도 장부에 있을 수 있는 줄이다")
    void 가드의_기준_수는_확실한_줄과_불확실_줄을_합친_수다() {
        // given — 확실한 줄 7 + 불확실 줄 3 = 10(최소 기준). 확실한 줄만으로는 가드를 적용하지 않는 크기다. 목표는 0건
        Set<RelationTuple> 확실 = IntStream.range(0, 7)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002")).collect(Collectors.toSet());
        Set<RelationTuple> 불확실 = IntStream.range(7, 10)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002")).collect(Collectors.toSet());
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 확실, 불확실)).block();
        source.willReturn(DirectorySnapshot.empty());

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 지울 10건이 기준 10건의 100% 라 가드가 걸린다
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("삭제 대상 10건").contains("기준 스냅샷 10건");
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("훑어 맞추기에서 결과를 모르는 지우기도 불확실 줄로 남고, 다음 회차가 목표에 다시 들었으면 쓴다")
    void 훑어_맞추기에서_결과를_모르는_지우기는_불확실_줄로_남는다() {
        // given — 지난 회차가 기록 전에 멈췄다. 장부에 kim, park. 목표는 kim 뿐이라 park 을 지우는데 서버가 반영하고 응답을 잃는다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        writer.stored.addAll(Set.of(김, 박));
        snapshots.writing.set(true);
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.lostResponseFor(tuple -> tuple.equals(박));

        // when
        var run1 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — park 은 확실한 줄이 아니라 불확실 줄이다. 표시는 스냅샷 저장으로 사라진다
        assertThat(run1.status()).isEqualTo(SyncStatus.PARTIAL);
        TupleSnapshot 첫째 = snapshots.saved.get(0);
        assertThat(첫째.tuples()).containsExactly(김);
        assertThat(첫째.uncertain()).containsExactly(박);
        겹치지_않는다(첫째);
        assertThat(snapshots.writing).isFalse();

        // given — park 이 되돌아왔다
        writer.lostResponseFor(tuple -> false);
        source.willReturn(조직도(Set.of("kim", "park"), "DEV002"));

        // when
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run2.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).containsExactlyInAnyOrder(김, 박);
        assertThat(snapshots.saved.get(1).tuples()).containsExactlyInAnyOrder(김, 박);
        assertThat(snapshots.saved.get(1).uncertain()).isEmpty();
    }

    // ---------- 가드가 걸려도 비활성 회수는 먼저 (설계 2026-10-20 §5) ----------

    private static final RelationTuple 막힌_직원 = RelationTuple.directMember("user0", "DEV002");

    /**
     * 기준선 20줄(user0~19)이 장부에도 있다. 디렉터리에는 user1~5 만 남고 user0 은 막혔다(그림과 소속에 그대로 있다). 지울 줄 15건(75%)으로 가드가
     * 걸리고, 그중 비활성으로 생긴 지우기는 user0 의 1건(5%)이다.
     */
    private Set<RelationTuple> 대부분이_빠지고_한_명이_막혔다(Set<String> 더_든_직원) {
        Set<RelationTuple> 기준 = 소속튜플(20, "DEV002");
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 기준)).block();
        writer.stored.addAll(기준);
        Set<String> 활성 = new HashSet<>(직원들(1, 6));
        활성.addAll(더_든_직원);
        source.willReturn(조직도(활성, Set.of("user0"), "DEV002"));
        return 기준;
    }

    @Test
    @DisplayName("삭제 가드가 걸려도 디렉터리가 막았다고 명시한 회수는 반영하고, 현재상태는 바꾸지 않으며 스냅샷은 남긴다")
    void 가드가_걸려도_비활성_회수는_반영하고_현재상태는_바꾸지_않는다() {
        // given
        Set<RelationTuple> 기준 = 대부분이_빠지고_한_명이_막혔다(Set.of());
        AtomicBoolean 쓸때_표시 = new AtomicBoolean();
        writer.onApply(() -> 쓸때_표시.set(snapshots.writing.get()));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 막힌 직원의 줄만 지운다. 가드가 걸린 회차라 ABORTED 이고, 무엇을 반영·보류했는지와 초과 사유가 함께 남는다
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message())
                .contains("비활성 회수 1건만 반영하고 나머지 지우기 14건·쓰기 0건은 보류")
                .contains(" / 삭제 대상 15건(기준 스냅샷 20건");
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(run.writtenCount()).isZero();
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactly(막힌_직원);
        assertThat(writer.appliedDeltas.get(0).toWrite()).isEmpty();
        assertThat(writer.stored).hasSize(19).doesNotContain(막힌_직원);

        // then — 현재상태는 바꾸지 않는다. 가드가 걸린 회차는 디렉터리 그림을 받아들이지 않았다
        assertThat(state.users).isEmpty();
        assertThat(state.groups).isEmpty();

        // then — 표시를 남기고 쓴 뒤 §3 의 식으로 스냅샷을 저장해 표시를 지운다
        assertThat(쓸때_표시).as("표시가 쓰기보다 먼저다").isTrue();
        assertThat(snapshots.saved).hasSize(2);
        assertThat(run.snapshotId()).isEqualTo(snapshots.saved.get(1).id());
        Set<RelationTuple> 남은_기준 = new HashSet<>(기준);
        남은_기준.remove(막힌_직원);
        assertThat(snapshots.saved.get(1).tuples()).containsExactlyInAnyOrderElementsOf(남은_기준);
        assertThat(snapshots.saved.get(1).uncertain()).isEmpty();
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("삭제 가드가 걸리면 같은 회차의 부여는 보류한다 — 옛 소속 지우기를 보류한 채 새 소속만 쓰면 이동자가 둘 다 갖는다")
    void 가드가_걸리면_부여는_보류한다() {
        // given — 같은 회차에 새 입사자가 들었다
        대부분이_빠지고_한_명이_막혔다(Set.of("newbie"));
        RelationTuple 새_입사자 = RelationTuple.directMember("newbie", "DEV002");

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("비활성 회수 1건만 반영하고 나머지 지우기 14건·쓰기 1건은 보류");
        assertThat(writer.written).isEmpty();
        assertThat(writer.stored).doesNotContain(새_입사자);
        assertThat(snapshots.saved.get(1).tuples()).doesNotContain(새_입사자);
        assertThat(snapshots.saved.get(1).uncertain()).doesNotContain(새_입사자);
    }

    @Test
    @DisplayName("비활성 회수만 반영한 다음 회차는 남은 지우기로 다시 가드를 본다 — 떼어 낼 회수가 없으면 하나도 지우지 않는다")
    void 비활성_회수만_반영한_다음_회차는_나머지로_다시_가드를_본다() {
        // given — 1회차가 막힌 직원의 줄만 지웠다
        대부분이_빠지고_한_명이_막혔다(Set.of());
        동기화한다(SyncTrigger.SCHEDULED);
        int 첫_회차까지 = writer.appliedDeltas.size();

        // when — 2회차도 디렉터리가 같다
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 남은 지울 줄 14건이 새 기준 19건의 74% 라 다시 걸리고, 비활성 회수는 이미 반영해 기준선에 없다
        assertThat(run2.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run2.deletedCount()).isZero();
        assertThat(run2.message()).contains("삭제 대상 14건(기준 스냅샷 19건").doesNotContain("비활성 회수");
        assertThat(writer.appliedDeltas).hasSize(첫_회차까지);
        assertThat(writer.stored).hasSize(19);
        assertThat(snapshots.saved).hasSize(2);
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("불확실 줄이 남은 채로 비활성 회수만 반영해도 식이 맞다 — 반영한 회수는 불확실 줄에서 풀리고, 보류한 줄은 불확실 줄에 남으며 확실한 줄과 겹치지 않는다")
    void 불확실_줄이_있는_채로_비활성_회수만_반영해도_식이_맞다() {
        // given — 기준선 21줄(user0~19, lee). 1회차에 새로 든 kim·choi 의 쓰기와 빠진 lee 의 지우기가 모두 응답을 잃는다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 최 = RelationTuple.directMember("choi", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        RelationTuple 막힌_오 = RelationTuple.directMember("user5", "DEV002");
        Set<RelationTuple> 기준 = new HashSet<>(소속튜플(20, "DEV002"));
        기준.add(이);
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 기준)).block();
        writer.stored.addAll(기준);
        Set<String> 처음 = new HashSet<>(직원들(0, 20));
        처음.addAll(Set.of("kim", "choi"));
        source.willReturn(조직도(처음, Set.of(), "DEV002"));
        writer.lostResponseFor(tuple -> tuple.equals(김) || tuple.equals(최) || tuple.equals(이));
        동기화한다(SyncTrigger.SCHEDULED);
        TupleSnapshot 첫째 = snapshots.saved.get(1);
        assertThat(첫째.uncertain()).containsExactlyInAnyOrder(김, 최, 이);
        assertThat(첫째.tuples()).containsExactlyInAnyOrderElementsOf(소속튜플(20, "DEV002"));

        // given — 2회차: user0~4 와 choi 만 활성이고 user5 와 kim 은 막혔다. lee 는 여전히 없다
        writer.lostResponseFor(tuple -> false);
        Set<String> 활성 = new HashSet<>(직원들(0, 5));
        활성.add("choi");
        source.willReturn(조직도(활성, Set.of("user5", "kim"), "DEV002"));

        // when
        var run2 = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 지울 17줄(user5~19, kim, lee)이 기준 23줄(확실 20 + 불확실 3)의 74% 라 가드가 걸린다.
        // 비활성 회수 2줄(user5, kim — kim 은 불확실 줄이다)만 지우고 choi 쓰기 1건·나머지 지우기 15건은 보류한다
        assertThat(run2.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run2.message())
                .contains("비활성 회수 2건만 반영하고 나머지 지우기 15건·쓰기 1건은 보류")
                .contains("기준 스냅샷 23건");
        var 마지막 = writer.appliedDeltas.get(writer.appliedDeltas.size() - 1);
        assertThat(마지막.toDelete()).containsExactlyInAnyOrder(막힌_오, 김);
        assertThat(마지막.toWrite()).isEmpty();
        assertThat(writer.stored).doesNotContain(막힌_오, 김);

        // then — 반영한 kim 은 불확실 줄에서 풀리고, 보류한 choi(쓰기)·lee(지우기)는 불확실 줄에 남는다. 확실한 줄에서는 user5 만 빠진다
        TupleSnapshot 둘째 = snapshots.saved.get(2);
        겹치지_않는다(둘째);
        assertThat(둘째.uncertain()).containsExactlyInAnyOrder(최, 이);
        Set<RelationTuple> 남은_기준 = new HashSet<>(소속튜플(20, "DEV002"));
        남은_기준.remove(막힌_오);
        assertThat(둘째.tuples()).containsExactlyInAnyOrderElementsOf(남은_기준);
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("비활성 회수만 반영하다 차단기로 멈춰도 나간 지우기로 스냅샷을 남기고 현재상태는 바꾸지 않는다")
    void 비활성_회수만_반영하다_차단기로_멈춰도_현재상태는_바꾸지_않는다() {
        // given
        대부분이_빠지고_한_명이_막혔다(Set.of());
        writer.abortWhen(delta -> true);

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 멈춘 것은 FAILED 로 남되, 가드가 걸려 일부만 반영하던 회차라는 사정도 남는다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("비활성 회수 1건만 반영").contains("연속 실패로 멈췄다");
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(snapshots.saved).hasSize(2);
        assertThat(snapshots.saved.get(1).tuples()).hasSize(19).doesNotContain(막힌_직원);
        assertThat(snapshots.writing).isFalse();
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("FORCED 는 지금처럼 가드를 건너뛴다 — 막힌 직원만이 아니라 지울 줄을 모두 지우고 현재상태도 바꾼다")
    void 강제_실행은_비활성_회수만이_아니라_모두_지운다() {
        // given
        대부분이_빠지고_한_명이_막혔다(Set.of());

        // when
        var run = 동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.deletedCount()).isEqualTo(15);
        assertThat(writer.stored).hasSize(5);
        assertThat(state.users).containsKeys("user0", "user1");
    }

    @Test
    @DisplayName("훑어 맞추기도 가드가 걸리면 비활성 회수만 지우고, 보류한 후보는 기준선에 남겨 스냅샷을 저장한다 — 표시가 꺼지고 현재상태는 그대로다")
    void 훑어_맞추기도_가드가_걸리면_비활성_회수만_지우고_보류한_후보는_기준선에_남긴다() {
        // given — 지난 회차가 기록 전에 멈췄다. 장부에 20줄, 디렉터리에는 user1~5 만 남고 user0 은 막혔다
        Set<RelationTuple> 장부 = 소속튜플(20, "DEV002");
        writer.stored.addAll(장부);
        snapshots.writing.set(true);
        source.willReturn(조직도(직원들(1, 6), Set.of("user0"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 훑은 장부 기준으로 가드가 걸리고 막힌 직원의 줄만 지운다. 이 길은 가드 전에 D 를 이미 썼으니 보류할 쓰기는 없다(설계 §5.4)
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message())
                .contains("기준선 의심")
                .contains("비활성 회수 1건만 반영하고 나머지 지우기 14건·쓰기 0건은 보류")
                .contains("훑은 장부 20건");
        assertThat(writer.deleted).containsExactly(막힌_직원);
        assertThat(run.deletedCount()).isEqualTo(1);

        // then — 스냅샷을 남겨 표시가 꺼진다. 보류한 후보(user6~19)는 장부에 그대로 있으니 기준선에 들어, 다음 회차는 비교 경로에서 같은 판정을 받는다
        assertThat(snapshots.writing).isFalse();
        assertThat(snapshots.saved).hasSize(1);
        Set<RelationTuple> 남은_장부 = new HashSet<>(장부);
        남은_장부.remove(막힌_직원);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrderElementsOf(남은_장부);
        assertThat(snapshots.saved.get(0).uncertain()).isEmpty();
        assertThat(state.users).isEmpty();
    }
}

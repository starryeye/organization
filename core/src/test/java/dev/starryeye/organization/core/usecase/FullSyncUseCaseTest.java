package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeSnapshotSource;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FullSyncUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-14T03:00:00Z");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private FullSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(고정시각);
        useCase = new FullSyncUseCase(source, snapshots, state, writer, runs,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
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

    @Test
    @DisplayName("최초 동기화는 읽어온 전체를 생성 대상으로 삼아 OpenFGA에 반영한다")
    void 최초_동기화는_전체를_생성한다() {
        // given
        source.willReturn(조직도(Set.of("kim", "lee"), "DEV002"));

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

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
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

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
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

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
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

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
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

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
        var run = useCase.execute(SyncTrigger.FORCED).block();

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
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

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
        useCase.execute(SyncTrigger.FORCED).block();

        // then
        assertThat(snapshots.saved.get(1).tuples()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("park", "DEV002"));
    }
}

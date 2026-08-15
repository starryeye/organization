package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeSnapshotSource;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RebuildUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-14T03:00:00Z");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private RebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(고정시각);
        useCase = new RebuildUseCase(source, snapshots, state, writer, runs,
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    private static DirectorySnapshot 조직도(String userId, String groupCode) {
        return new DirectorySnapshot(
                Map.of(userId, new DirectoryUser(userId, "uid=" + userId, userId, userId, null, true)),
                Map.of(groupCode, new DirectoryGroup(groupCode, "cn=" + groupCode, "백엔드팀",
                        Set.of(MemberRef.user(userId)))));
    }

    @Test
    @DisplayName("snapshot 모드는 직전 스냅샷으로 먼저 전부 삭제한 뒤에 스냅샷을 버린다")
    void snapshot_모드는_먼저_지우고_나중에_버린다() {
        // given — 직전 스냅샷에 lee 소속이 남아 있다
        var 낡은튜플 = RelationTuple.directMember("lee", "DEV002");
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, Set.of(낡은튜플))).block();
        source.willReturn(조직도("kim", "DEV002"));

        // when
        var run = useCase.execute(RebuildMode.SNAPSHOT).block();

        // then — 첫 델타가 삭제, 그 다음이 생성이어야 한다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.appliedDeltas).hasSize(2);
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactly(낡은튜플);
        assertThat(writer.appliedDeltas.get(1).toWrite())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
        assertThat(snapshots.resetCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("snapshot 모드가 끝나면 새 스냅샷과 현재상태가 최신으로 남는다")
    void snapshot_모드_후_상태가_최신이다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("lee", "DEV002")))).block();
        source.willReturn(조직도("kim", "DEV002"));

        // when
        useCase.execute(RebuildMode.SNAPSHOT).block();

        // then
        var latest = snapshots.findLatest().block();
        assertThat(latest.tuples()).containsExactly(RelationTuple.directMember("kim", "DEV002"));
        assertThat(state.users).containsOnlyKeys("kim");
    }

    @Test
    @DisplayName("직전 스냅샷이 없으면 삭제 단계를 건너뛰고 전체를 새로 적재한다")
    void 직전_스냅샷이_없으면_삭제를_건너뛴다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));

        // when
        var run = useCase.execute(RebuildMode.SNAPSHOT).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toWrite()).hasSize(1);
    }

    @Test
    @DisplayName("삭제 단계가 하나라도 실패하면 스냅샷을 버리지 않고 FAILED 로 끝낸다")
    void 삭제가_실패하면_스냅샷을_버리지_않는다() {
        // given
        var 낡은튜플 = RelationTuple.directMember("lee", "DEV002");
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, Set.of(낡은튜플))).block();
        source.willReturn(조직도("kim", "DEV002"));
        writer.failFor(tuple -> tuple.equals(낡은튜플));

        // when
        var run = useCase.execute(RebuildMode.SNAPSHOT).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.resetCount.get()).isZero();
        assertThat(snapshots.findLatest().block()).isNotNull();
    }

    @Test
    @DisplayName("store 모드는 store 를 재생성하고 스냅샷을 버린 뒤 전체를 적재한다")
    void store_모드는_store를_재생성한다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("lee", "DEV002")))).block();
        source.willReturn(조직도("kim", "DEV002"));

        // when
        var run = useCase.execute(RebuildMode.STORE).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.resetStoreCount.get()).isEqualTo(1);
        assertThat(snapshots.resetCount.get()).isEqualTo(1);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
    }

    @Test
    @DisplayName("재적재는 REBUILD 트리거로 이력에 기록된다")
    void 재적재는_REBUILD_트리거로_기록된다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));

        // when
        var run = useCase.execute(RebuildMode.SNAPSHOT).block();

        // then
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(runs.finished).hasSize(1);
    }

    @Test
    @DisplayName("mode 문자열을 대소문자 구분 없이 해석하고 알 수 없는 값은 거절한다")
    void mode_문자열을_해석한다() {
        // given, when, then
        assertThat(RebuildMode.from("snapshot")).isEqualTo(RebuildMode.SNAPSHOT);
        assertThat(RebuildMode.from("STORE")).isEqualTo(RebuildMode.STORE);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> RebuildMode.from("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

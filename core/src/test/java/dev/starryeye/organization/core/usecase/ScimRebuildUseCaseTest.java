package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimRebuildUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private MutationGate gate;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(NOW);
        gate = new MutationGate();
        useCase = new ScimRebuildUseCase(state, writer, snapshots, runs, gate,
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

    // ---------- tuples 모드 ----------

    @Test
    @DisplayName("tuples 모드는 store 를 비우고 현재상태가 요구하는 튜플을 전부 다시 쓴다")
    void tuples_모드는_전부_다시_쓴다() {
        // given
        조직도를_심는다();

        // when
        var run = useCase.execute(ScimRebuildMode.TUPLES).block();

        // then — 초기화가 먼저 일어나고
        assertThat(writer.resetStoreCount).hasValue(1);
        // 비활성 직원(lee)은 제외되고, 나머지는 전부 다시 쓰인다
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toWrite()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001"));
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("tuples 모드는 현재상태를 건드리지 않는다 — 상태가 곧 진실이다")
    void tuples_모드는_상태를_안_건드린다() {
        // given
        조직도를_심는다();

        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(state.users).containsOnlyKeys("kim", "lee");
        assertThat(state.groups).containsOnlyKeys("DEV002", "DEV001");
    }

    @Test
    @DisplayName("tuples 모드는 REBUILD 트리거로 SCIM 이력에 남고 실제로 쓴 것을 스냅샷에 기록한다")
    void tuples_모드는_이력과_스냅샷을_남긴다() {
        // given
        조직도를_심는다();

        // when
        var run = useCase.execute(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(run.source()).isEqualTo(SyncSource.SCIM);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.SCIM);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001"));
    }

    @Test
    @DisplayName("조직도가 비어 있어도 초기화만 하고 정상 종료한다")
    void 빈_조직도도_정상_종료한다() {
        // when
        var run = useCase.execute(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(writer.resetStoreCount).hasValue(1);
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("튜플 쓰기가 일부 실패하면 PARTIAL 로 남고 스냅샷에는 실제로 쓴 것만 담긴다")
    void 부분_실패는_PARTIAL이다() {
        // given
        조직도를_심는다();
        writer.failFor(tuple -> tuple.relation().equals(RelationTuple.CHILD));

        // when
        var run = useCase.execute(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    // ---------- wipe 모드 ----------

    @Test
    @DisplayName("wipe 모드는 store 를 비우고 현재상태의 직원·조직을 전부 지운다")
    void wipe_모드는_상태까지_비운다() {
        // given
        조직도를_심는다();

        // when
        var run = useCase.execute(ScimRebuildMode.WIPE).block();

        // then
        assertThat(writer.resetStoreCount).hasValue(1);
        assertThat(state.users).isEmpty();
        assertThat(state.groups).isEmpty();
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("wipe 모드는 튜플을 하나도 쓰지 않는다 — 상태가 비었으니 요구되는 튜플도 없다")
    void wipe_모드는_튜플을_안_쓴다() {
        // given
        조직도를_심는다();

        // when
        useCase.execute(ScimRebuildMode.WIPE).block();

        // then
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("wipe 모드는 RESET 트리거로 이력에 남고, 감사 이력은 지우지 않는다")
    void wipe_모드는_감사_이력을_남긴다() {
        // given — 지우기 전에 이미 쌓여 있던 스냅샷이 있다
        조직도를_심는다();
        useCase.execute(ScimRebuildMode.TUPLES).block();
        int 지우기_전_스냅샷 = snapshots.saved.size();

        // when
        var run = useCase.execute(ScimRebuildMode.WIPE).block();

        // then — 사고 뒤에 무슨 일이 있었는지 볼 유일한 기록이라 남긴다
        assertThat(run.trigger()).isEqualTo(SyncTrigger.RESET);
        assertThat(snapshots.resetCount).hasValue(0);
        assertThat(snapshots.saved).hasSize(지우기_전_스냅샷);
        assertThat(runs.finished).hasSize(2);
    }

    @Test
    @DisplayName("OpenFGA 초기화가 실패하면 현재상태를 건드리지 않는다")
    void 초기화_실패시_상태를_지키다() {
        // given — 여기서 순서가 뒤집혀 있으면 조직도가 사라지고 낡은 권한만 남는다
        조직도를_심는다();
        writer.failResetStore(new IllegalStateException("OpenFGA 접속 불가"));

        // when
        var run = useCase.execute(ScimRebuildMode.WIPE).block();

        // then — 조직도는 온전하고, tuples 모드 한 번이면 복구된다
        assertThat(state.users).containsOnlyKeys("kim", "lee");
        assertThat(state.groups).containsOnlyKeys("DEV002", "DEV001");
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
    }

    // ---------- 게이트 ----------

    @Test
    @DisplayName("재적재가 도는 동안 게이트가 닫혀 있다")
    void 도는_동안_게이트가_닫힌다() {
        // given
        조직도를_심는다();
        writer.onApply(() -> assertThat(gate.isSuspended()).isTrue());

        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();

        // then — 끝나면 열린다
        assertThat(gate.isSuspended()).isFalse();
    }

    @Test
    @DisplayName("재적재가 실패해도 게이트는 반납된다")
    void 실패해도_게이트를_반납한다() {
        // given
        조직도를_심는다();
        writer.failResetStore(new IllegalStateException("터짐"));

        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();

        // then — 안 반납하면 이후 모든 SCIM 변경이 영구히 503 이 된다
        assertThat(gate.isSuspended()).isFalse();
    }

    @Test
    @DisplayName("이미 재적재가 돌고 있으면 두 번째 요청은 거절된다")
    void 겹친_재적재는_거절한다() {
        // given
        gate.acquire();

        // when, then
        assertThatThrownBy(() -> useCase.execute(ScimRebuildMode.TUPLES).block())
                .isInstanceOf(MutationsSuspendedException.class);

        // 남의 게이트를 반납해버리면 안 된다
        assertThat(gate.isSuspended()).isTrue();
        assertThat(writer.resetStoreCount).hasValue(0);
    }
}

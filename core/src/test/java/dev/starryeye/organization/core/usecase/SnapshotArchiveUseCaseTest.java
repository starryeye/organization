package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
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

class SnapshotArchiveUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-15T03:00:00Z");

    private FakeStateRepository state;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private SnapshotArchiveUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(고정시각);
        useCase = new SnapshotArchiveUseCase(state, snapshots, runs,
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("현재상태를 튜플로 바꿔 SCIM 소스 스냅샷으로 적재한다")
    void 현재상태를_스냅샷으로_적재한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.SCIM);
        assertThat(snapshots.saved.get(0).id()).isEqualTo("20260815T030000000-SCIM");
        assertThat(snapshots.saved.get(0).tuples())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("아카이빙은 ARCHIVE 트리거로 이력에 남는다")
    void ARCHIVE_트리거로_기록된다() {
        // given, when
        var run = useCase.execute().block();

        // then
        assertThat(run.trigger()).isEqualTo(SyncTrigger.ARCHIVE);
        assertThat(run.source()).isEqualTo(SyncSource.SCIM);
        assertThat(runs.finished).hasSize(1);
    }

    @Test
    @DisplayName("현재상태가 비어 있어도 빈 스냅샷을 남긴다")
    void 비어_있어도_스냅샷을_남긴다() {
        // given, when
        var run = useCase.execute().block();

        // then — 그날 조직이 비었다는 사실 자체가 기록이다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }
}

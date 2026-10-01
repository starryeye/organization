package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.MutationLock;
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
    private FakeTupleChecker checker;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private SnapshotArchiveUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        checker = new FakeTupleChecker();
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(고정시각);
        lock = new FakeMutationLock();
        useCase = new SnapshotArchiveUseCase(state, checker, snapshots, runs, lock,
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("OpenFGA 에 실제로 있는 튜플을 SCIM 소스 스냅샷으로 적재한다")
    void 실제_튜플을_스냅샷으로_적재한다() {
        // given — 상태와 OpenFGA 가 일치하는 평상시
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.allowed.add(RelationTuple.directMember("kim", "DEV002"));

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
    @DisplayName("상태가 요구하는 것이 아니라 실제 있는 것을 적재한다 — 둘이 어긋나 있어도")
    void 어긋나면_실제를_적재한다() {
        // given — 상태는 kim 의 소속을 말하지만 OpenFGA 에는 그 튜플이 없다.
        // 전에는 상태로부터 유도해 저장했으므로 이 스냅샷이 "있었다" 고 거짓말했다.
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        // checker.allowed 가 비어 있다 = OpenFGA 에 아무것도 없다

        // when
        var run = useCase.execute().block();

        // then — 감사 기록은 관찰이어야 한다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("BatchCheck 가 실패하면 의도한 튜플로 폴백하지 않고 아카이빙을 실패로 끝낸다")
    void Check_실패는_폴백하지_않는다() {
        // given — 폴백하면 그 스냅샷이 관찰인지 유도인지 구분되지 않는다
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.failFor(tuple -> true);

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.saved).isEmpty();
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

    @Test
    @DisplayName("재적재가 락을 쥐고 있으면 오늘 아카이빙을 건너뛰고 ABORTED 로 남긴다 — 잠깐 어긋난 장부를 '실제'로 찍지 않게")
    void 재적재_중이면_건너뛴다() {
        // given
        lock.acquire(MutationLock.LockPurpose.REBUILD).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).isEqualTo("재적재 중이라 건너뜀");
        assertThat(snapshots.saved).isEmpty();
    }

    @Test
    @DisplayName("SCIM 쓰기가 잠깐 쥔 락은 건너뛸 이유가 아니다")
    void 쓰기_락은_건너뛰지_않는다() {
        // given
        lock.acquire(MutationLock.LockPurpose.WRITE).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }
}

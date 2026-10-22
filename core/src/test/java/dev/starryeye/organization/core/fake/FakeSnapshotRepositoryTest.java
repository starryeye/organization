package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.TupleSnapshotRepository.BaselineState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 스냅샷 저장소가 진짜처럼 포인터 상태를 셋으로 읽는지 확인한다. 기준선 없음·기록 중 시험이 이것에 기대므로 여기가 틀리면 그 위의 시험이 헛돈다.
 */
class FakeSnapshotRepositoryTest {

    private static final Instant 시각 = Instant.parse("2026-10-20T03:00:00Z");

    private static TupleSnapshot 스냅샷() {
        return new TupleSnapshot("S1", 시각, SyncSource.LDAP, Set.of(RelationTuple.directMember("kim", "DEV001")));
    }

    @Test
    @DisplayName("저장한 것이 없으면 기준선 없음이다")
    void 저장한_것이_없으면_기준선_없음이다() {
        // given
        var snapshots = new FakeSnapshotRepository();

        // when, then
        assertThat(snapshots.baselineState().block()).isEqualTo(BaselineState.NONE);
    }

    @Test
    @DisplayName("스냅샷을 저장하면 기준선 있음이다")
    void 스냅샷을_저장하면_기준선_있음이다() {
        // given
        var snapshots = new FakeSnapshotRepository();

        // when
        snapshots.save(스냅샷()).block();

        // then
        assertThat(snapshots.baselineState().block()).isEqualTo(BaselineState.PRESENT);
    }

    @Test
    @DisplayName("기록 중 표시가 있으면 저장한 것이 있든 없든 기록 중이고, 저장하면 표시가 사라진다")
    void 기록_중_표시가_있으면_기록_중이다() {
        // given — 저장한 것이 없는데 표시만 남은 경우
        var snapshots = new FakeSnapshotRepository();
        snapshots.markWriting().block();
        assertThat(snapshots.baselineState().block()).isEqualTo(BaselineState.WRITING);

        // when — 저장하면 표시가 사라진다
        snapshots.save(스냅샷()).block();
        assertThat(snapshots.baselineState().block()).isEqualTo(BaselineState.PRESENT);

        // then — 저장한 것이 있어도 표시가 다시 켜지면 기록 중이다
        snapshots.markWriting().block();
        assertThat(snapshots.baselineState().block()).isEqualTo(BaselineState.WRITING);
    }
}

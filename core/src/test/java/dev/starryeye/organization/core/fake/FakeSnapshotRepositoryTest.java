package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.BaselineMovedException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository.BaselinePointer;
import dev.starryeye.organization.core.port.TupleSnapshotRepository.BaselineState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final RelationTuple A = RelationTuple.directMember("a", "DEV001");
    private static final RelationTuple B = RelationTuple.directMember("b", "DEV001");
    private static final RelationTuple C = RelationTuple.directMember("c", "DEV001");

    // 깨뜨릴 변경: settleRevocation 이 gone 을 unknown 에서 빼지 않거나 dropped 를 gone 칸에 넣으면 실패한다
    @Test
    @DisplayName("표시를 적고 정리하면 지운 줄은 gone 으로 옮기고 버린 줄은 빠진다")
    void 표시를_적고_정리하면_지운_줄은_gone_으로_옮기고_버린_줄은_빠진다() {
        // given
        var snapshots = new FakeSnapshotRepository();
        snapshots.save(스냅샷()).block();
        snapshots.markRevoking("S1", Set.of(A, B, C)).block();

        // when
        snapshots.settleRevocation("S1", Set.of(A), Set.of(B)).block();

        // then
        assertThat(snapshots.marks().gone()).containsExactly(A);
        assertThat(snapshots.marks().unknown()).containsExactly(C);
        var marked = snapshots.findLatestMarked().block();
        assertThat(marked.marks().gone()).containsExactly(A);
        assertThat(marked.marks().unknown()).containsExactly(C);
    }

    // 깨뜨릴 변경: 스냅샷 번호나 기록 중 조건을 보지 않으면 실패한다
    @Test
    @DisplayName("스냅샷 번호가 다르거나 기록 중이면 BaselineMovedException")
    void 스냅샷_번호가_다르거나_기록_중이면_BaselineMovedException() {
        // given
        var snapshots = new FakeSnapshotRepository();
        snapshots.save(스냅샷()).block();

        // when, then — 번호가 다르다
        assertThatThrownBy(() -> snapshots.markRevoking("S9", Set.of(A)).block())
                .isInstanceOf(BaselineMovedException.class);
        assertThatThrownBy(() -> snapshots.settleRevocation("S9", Set.of(A), Set.of()).block())
                .isInstanceOf(BaselineMovedException.class);
        assertThat(snapshots.marks().isEmpty()).isTrue();

        // when, then — 기록 중이다
        snapshots.markWriting().block();
        assertThatThrownBy(() -> snapshots.markRevoking("S1", Set.of(A)).block())
                .isInstanceOf(BaselineMovedException.class);
        assertThatThrownBy(() -> snapshots.settleRevocation("S1", Set.of(A), Set.of()).block())
                .isInstanceOf(BaselineMovedException.class);
        assertThat(snapshots.marks().isEmpty()).isTrue();
    }

    // 깨뜨릴 변경: save 가 표시를 지우지 않거나 markWriting 이 표시를 지우면 실패한다
    @Test
    @DisplayName("save 는 표시를 지우고 markWriting 은 남긴다")
    void save_는_표시를_지우고_markWriting_은_남긴다() {
        // given
        var snapshots = new FakeSnapshotRepository();
        snapshots.save(스냅샷()).block();
        snapshots.markRevoking("S1", Set.of(A)).block();

        // when — markWriting
        snapshots.markWriting().block();

        // then
        assertThat(snapshots.marks().unknown()).containsExactly(A);

        // when — save
        snapshots.save(new TupleSnapshot("S2", 시각, SyncSource.LDAP, Set.of(A))).block();

        // then
        assertThat(snapshots.marks().isEmpty()).isTrue();
    }

    // 깨뜨릴 변경: baselinePointer 가 번호를 싣지 않거나 상태를 잘못 읽으면 실패한다
    @Test
    @DisplayName("baselinePointer 는 상태와 포인터 번호를 준다")
    void baselinePointer_는_상태와_포인터_번호를_준다() {
        // given
        var snapshots = new FakeSnapshotRepository();

        // when, then
        assertThat(snapshots.baselinePointer().block()).isEqualTo(new BaselinePointer(BaselineState.NONE, null));
        snapshots.save(스냅샷()).block();
        assertThat(snapshots.baselinePointer().block()).isEqualTo(new BaselinePointer(BaselineState.PRESENT, "S1"));
        snapshots.markWriting().block();
        assertThat(snapshots.baselinePointer().block()).isEqualTo(new BaselinePointer(BaselineState.WRITING, "S1"));
    }
}

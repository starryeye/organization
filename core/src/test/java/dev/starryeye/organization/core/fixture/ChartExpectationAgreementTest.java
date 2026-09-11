package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChartExpectation} 과 운영의 {@link TupleMapper} 가 <b>만나는 유일한 곳.</b>
 *
 * <p>둘은 같은 규칙을 따로 쓴 것이다. 순환도 끊긴 참조도 없는 조직도에서는 같은 튜플을 요구해야
 * 한다. 갈리면 둘 중 하나가 틀렸다 — 하네스의 기대값을 운영에서 가져오지 않기로 한 대가로, 둘이
 * 같다는 사실을 여기서 따로 확인한다 (스펙 §3).
 */
class ChartExpectationAgreementTest {

    private final OrgChart chart = OrgChartFixture.오천명();

    @Test
    @DisplayName("규모 조직도에서 TupleMapper 와 같은 튜플을 요구한다")
    void 규모_조직도에서_같다() {
        // when
        var 기대 = ChartExpectation.of(chart).있어야할튜플();

        // then
        assertThat(기대).isEqualTo(TupleMapper.toTuples(chart.snapshot()).tuples());
    }

    @Test
    @DisplayName("비활성 직원이 섞여도 같다")
    void 비활성이_섞여도_같다() {
        // given
        var l = chart.landmarks();
        OrgChart 섞인것 = OrgChartEditor.편집한다(chart)
                .비활성으로_바꾼다(l.L4직속직원())
                .비활성으로_바꾼다(l.겸직직원())
                .완성();

        // when
        var 기대 = ChartExpectation.of(섞인것).있어야할튜플();

        // then
        assertThat(기대).isEqualTo(TupleMapper.toTuples(섞인것.snapshot()).tuples());
    }

    @Test
    @DisplayName("물어볼 후보는 TupleMapper 의 후보를 모두 포함한다")
    void 후보는_운영의_후보를_포함한다() {
        // given
        OrgChart 편집후 = OrgChartEditor.편집한다(chart)
                .직원을_지운다(chart.landmarks().L6직속직원())
                .완성();

        // when
        var 후보 = ChartExpectation.of(편집후).물어볼후보();

        // then — 지워진 멤버십만큼 더 많다
        assertThat(후보).containsAll(TupleMapper.candidateTuples(편집후.snapshot()));
        assertThat(후보).hasSizeGreaterThan(TupleMapper.candidateTuples(편집후.snapshot()).size());
    }
}

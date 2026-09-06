package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 픽스처가 시나리오 문서 §2 의 형상을 실제로 만드는지 확인한다.
 *
 * <p>픽스처가 틀리면 그 위에 얹힌 E2E 전부가 엉뚱한 것을 검증한다. 그래서 픽스처 자체에
 * 테스트가 붙는다.
 */
class OrgChartFixtureTest {

    private final OrgChart chart = OrgChartFixture.오천명();

    @Test
    @DisplayName("규모가 임계값을 전부 넘긴다 — 페이징·배치·청크")
    void 임계값을_전부_넘긴다() {
        // given
        int 직원 = chart.snapshot().users().size();
        long 튜플 = chart.멤버십수() + chart.child간선수();

        System.out.println(OrgChartFixture.요약(chart));

        // then — 넘겨야 하는 것들 (시나리오 문서 §2)
        assertThat(직원).as("LDAP page-size 500 을 여러 번 넘겨야 한다").isGreaterThan(2_500);
        assertThat(튜플).as("OpenFGA write-batch-size 100 을 여러 번 넘겨야 한다").isGreaterThan(1_000);
        assertThat(chart.snapshot().groups()).hasSizeGreaterThan(300);
    }

    @Test
    @DisplayName("깊이가 부문마다 다르다 — 롤업 체인 길이가 흩어진다")
    void 깊이가_들쭉날쭉하다() {
        // given
        var l = chart.landmarks();

        // when — 각 대표 직원의 직속 조직에서 조상 체인을 잰다
        int 짧은체인 = 체인길이(l.L3직속직원());
        int 긴체인 = 체인길이(l.L6직속직원());

        // then — 균일 깊이면 이 둘이 같아져 짧은 체인 검증이 사라진다
        assertThat(짧은체인).isLessThan(긴체인);
        assertThat(체인길이(l.L2직속직원())).isLessThan(짧은체인);
    }

    /** 대표 직원은 소속이 하나여야 한다 — 겸직이면 "체인 길이" 라는 말 자체가 성립하지 않는다. */
    private int 체인길이(String userId) {
        return 1 + chart.조상들(chart.직속조직(userId)).size();
    }

    @Test
    @DisplayName("모든 중간 조직이 직속 직원과 하위 조직을 동시에 갖는다")
    void 중간조직은_직속과_자식을_모두_갖는다() {
        // given — 자식이 있는 조직들 (말단과 빈 조직 제외)
        var 중간조직 = chart.snapshot().groups().values().stream()
                .filter(group -> group.members().stream().anyMatch(m -> m.type() == MemberType.GROUP))
                .filter(group -> !group.id().startsWith("EMPTY_"))
                .toList();

        // then — 자식이 있으면서 직속 직원도 있어야 한다.
        // 한쪽만 있으면 그 조직의 member 에 direct_member 와 자식 롤업이 함께 기여하는
        // 경우가 만들어지지 않는다.
        assertThat(중간조직).isNotEmpty();
        assertThat(중간조직).allSatisfy(group ->
                assertThat(group.members()).anyMatch(m -> m.type() == MemberType.USER));
    }

    @Test
    @DisplayName("겸직 직원은 깊이가 다른 두 부문에 동시에 속한다")
    void 겸직은_깊이가_다른_두_부문에_걸친다() {
        // given
        String 겸직 = chart.landmarks().겸직직원();

        // when
        Set<String> 직속 = chart.직속조직들(겸직);

        // then — 두 조직, 그리고 조상 체인 길이가 서로 다르다
        assertThat(직속).hasSize(2);
        var 길이들 = 직속.stream().map(org -> chart.조상들(org).size()).distinct().toList();
        assertThat(길이들).as("같은 깊이끼리 걸치면 다중 경로 검증의 값이 떨어진다").hasSize(2);
    }

    @Test
    @DisplayName("대형 조직이 BatchCheck 청크(50)를 여러 번 넘긴다")
    void 대형조직이_청크를_넘긴다() {
        // given, when
        var 대형 = chart.snapshot().groups().get(chart.landmarks().대형조직());

        // then
        assertThat(대형.members()).hasSizeGreaterThan(50 * 5);
    }

    @Test
    @DisplayName("빈 조직이 있어 빈 델타 경로를 탄다")
    void 빈_조직이_있다() {
        // given, when, then
        assertThat(chart.landmarks().빈조직들()).isNotEmpty();
        assertThat(chart.landmarks().빈조직들()).allSatisfy(code ->
                assertThat(chart.snapshot().groups().get(code).members()).isEmpty());
    }

    @Test
    @DisplayName("조직도에 순환이 없어 TupleMapper 가 경고 없이 통과한다")
    void 순환이_없다() {
        // when
        var mapping = TupleMapper.toTuples(chart.snapshot());

        // then — 픽스처가 이미 깨져 있으면 시나리오가 무엇을 검증하는지 알 수 없다
        assertThat(mapping.warnings()).isEmpty();
        assertThat(mapping.tuples())
                .hasSize((int) (chart.멤버십수() + chart.child간선수()));
    }

    @Test
    @DisplayName("기대소속은 직속과 그 조상을 모두 담는다 — 롤업 검증의 기준")
    void 기대소속이_조상까지_담는다() {
        // given
        String 깊은직원 = chart.landmarks().L6직속직원();
        String 직속 = chart.직속조직(깊은직원);

        // when
        Set<String> 기대 = chart.기대소속(깊은직원);

        // then
        assertThat(기대).contains(직속);
        assertThat(기대).containsAll(chart.조상들(직속));
        assertThat(기대).contains(chart.landmarks().회사());
    }

    @Test
    @DisplayName("두 번 만들어도 같은 조직도가 나온다 — 결정론적")
    void 결정론적이다() {
        // when
        var 다시 = OrgChartFixture.오천명();

        // then
        assertThat(다시.snapshot()).isEqualTo(chart.snapshot());
        assertThat(다시.landmarks()).isEqualTo(chart.landmarks());
    }

    @Test
    @DisplayName("깊이별 대표 직원은 모두 소속이 하나다 — 겸직이 섞이면 체인이 둘이 된다")
    void 대표직원은_소속이_하나다() {
        // given
        var l = chart.landmarks();

        // then — 겸직 직원과 겹치면 "가장 긴 롤업 체인" 같은 말이 성립하지 않는다
        assertThat(List.of(l.L2직속직원(), l.L3직속직원(), l.L4직속직원(),
                        l.L5직속직원(), l.L6직속직원()))
                .allSatisfy(userId -> assertThat(chart.직속조직들(userId)).hasSize(1));
    }
}

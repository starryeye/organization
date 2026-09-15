package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 에디터가 <b>지운 것을 기억하는지</b> 확인한다.
 *
 * <p>하네스는 조직도에 있는 멤버십만 묻는다. 지운 멤버십을 잊으면 "지웠어야 할 권한이 남았다"
 * 를 영원히 못 묻는다 — 퇴사자 권한 생존이 정확히 그 모양이다.
 */
class OrgChartEditorTest {

    private final OrgChart chart = OrgChartFixture.오천명();

    @Test
    @DisplayName("최초 조직도는 기억이 비어 있다")
    void 최초_조직도는_기억이_없다() {
        // then
        assertThat(chart.지워진멤버십()).isEmpty();
    }

    @Test
    @DisplayName("직원을 옮기면 옛 조직의 멤버십을 기억한다")
    void 이동하면_옛_소속을_기억한다() {
        // given
        String 직원 = chart.landmarks().L5직속직원();
        String 옛조직 = chart.직속조직(직원);
        String 새조직 = chart.landmarks().대상팀();
        assertThat(새조직).as("전제: 다른 조직으로 옮겨야 한다").isNotEqualTo(옛조직);

        // when
        OrgChart 이동후 = OrgChartEditor.편집한다(chart).직원을_옮긴다(직원, 옛조직, 새조직).완성();

        // then
        assertThat(이동후.지워진멤버십())
                .containsExactly(new Membership(옛조직, MemberRef.user(직원)));
    }

    @Test
    @DisplayName("직원을 지우면 모든 소속 조직의 멤버십을 기억한다 — 겸직이면 둘 다")
    void 삭제하면_모든_소속을_기억한다() {
        // given
        String 겸직 = chart.landmarks().겸직직원();
        var 소속들 = chart.직속조직들(겸직);
        assertThat(소속들).hasSize(2);

        // when
        OrgChart 삭제후 = OrgChartEditor.편집한다(chart).직원을_지운다(겸직).완성();

        // then
        assertThat(삭제후.지워진멤버십()).containsExactlyInAnyOrderElementsOf(
                소속들.stream().map(org -> new Membership(org, MemberRef.user(겸직))).toList());
    }

    @Test
    @DisplayName("조직을 지우면 그 조직 안의 멤버십과 상위 조직과의 연결까지 기억한다")
    void 조직을_지우면_안의_멤버까지_기억한다() {
        // given — 조직 레코드가 통째로 사라지는 경로. 연산마다 기록하는 방식이면 여기서 놓친다
        String 실 = chart.landmarks().삭제할실();
        String 부모 = chart.부모(실);
        var 안의멤버 = chart.snapshot().groups().get(실).members();
        assertThat(안의멤버).isNotEmpty();

        // when
        OrgChart 삭제후 = OrgChartEditor.편집한다(chart).조직을_지운다(실).완성();

        // then
        assertThat(삭제후.지워진멤버십()).contains(new Membership(부모, MemberRef.group(실)));
        assertThat(삭제후.지워진멤버십()).containsAll(
                안의멤버.stream().map(member -> new Membership(실, member)).toList());
    }

    @Test
    @DisplayName("멤버를 통째로 비우면 직원과 하위 조직 멤버십을 모두 기억한다")
    void 통째로_비우면_전부_기억한다() {
        // given
        String 팀 = chart.landmarks().대상팀();
        var 멤버들 = chart.snapshot().groups().get(팀).members();

        // when
        OrgChart 비운후 = OrgChartEditor.편집한다(chart).멤버를_모두_비운다(팀).완성();

        // then
        assertThat(비운후.지워진멤버십()).containsExactlyInAnyOrderElementsOf(
                멤버들.stream().map(member -> new Membership(팀, member)).toList());
    }

    @Test
    @DisplayName("편집을 이어 가면 기억도 이어진다")
    void 기억은_누적된다() {
        // given
        String 첫째 = chart.landmarks().L6직속직원();
        String 둘째 = chart.landmarks().L4직속직원();
        OrgChart 한번 = OrgChartEditor.편집한다(chart).직원을_지운다(첫째).완성();

        // when
        OrgChart 두번 = OrgChartEditor.편집한다(한번).직원을_지운다(둘째).완성();

        // then
        assertThat(두번.지워진멤버십()).contains(
                new Membership(chart.직속조직(첫째), MemberRef.user(첫째)),
                new Membership(chart.직속조직(둘째), MemberRef.user(둘째)));
    }

    @Test
    @DisplayName("비활성으로 바꾸면 멤버십은 그대로라 기억할 것이 없다")
    void 비활성화는_멤버십을_안_지운다() {
        // given
        String 직원 = chart.landmarks().L4직속직원();

        // when
        OrgChart 바꾼후 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(직원).완성();

        // then
        assertThat(바꾼후.snapshot().users().get(직원).active()).isFalse();
        assertThat(바꾼후.직속조직들(직원)).isEqualTo(chart.직속조직들(직원));
        assertThat(바꾼후.지워진멤버십()).isEmpty();
    }

    @Test
    @DisplayName("활성으로 되돌리면 active 만 바뀐다")
    void 재활성화는_active만_바꾼다() {
        // given
        String 직원 = chart.landmarks().L4직속직원();
        OrgChart 비활성 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(직원).완성();

        // when
        OrgChart 되돌린후 = OrgChartEditor.편집한다(비활성).활성으로_바꾼다(직원).완성();

        // then
        assertThat(되돌린후.snapshot().users().get(직원))
                .isEqualTo(chart.snapshot().users().get(직원));
    }

    @Test
    @DisplayName("비활성 직원을 넣으면 레코드와 멤버십이 함께 생긴다")
    void 비활성_직원을_넣는다() {
        // given
        String 팀 = chart.landmarks().대상파트();

        // when
        OrgChart 넣은후 = OrgChartEditor.편집한다(chart).비활성_직원을_넣는다(팀, "scim.inactive").완성();

        // then
        var 직원 = 넣은후.snapshot().users().get("scim.inactive");
        assertThat(직원.active()).isFalse();
        assertThat(직원.displayName()).isEqualTo("비활성 직원");
        assertThat(직원.email()).isNull();
        assertThat(넣은후.직속조직들("scim.inactive")).containsExactly(팀);
    }

    @Test
    @DisplayName("직원 레코드만 지우면 멤버 목록의 참조는 남는다 — 조직이 먼저 도착한 중간 상태")
    void 레코드만_지우면_참조가_남는다() {
        // given
        String 직원 = chart.landmarks().L6직속직원();
        String 조직 = chart.직속조직(직원);

        // when
        OrgChart 지운후 = OrgChartEditor.편집한다(chart).직원_레코드만_지운다(직원).완성();

        // then
        assertThat(지운후.snapshot().users()).doesNotContainKey(직원);
        assertThat(지운후.snapshot().groups().get(조직).members()).contains(MemberRef.user(직원));
        assertThat(지운후.지워진멤버십()).isEmpty();
    }

    @Test
    @DisplayName("없는 직원을 편집하면 거부한다 — 조용히 넘기면 기대값이 틀린 채 통과한다")
    void 없는_직원은_거부한다() {
        // when, then
        assertThatThrownBy(() -> OrgChartEditor.편집한다(chart).비활성으로_바꾼다("nobody"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrgChartEditor.편집한다(chart).직원_레코드만_지운다("nobody"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

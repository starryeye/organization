package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrgChartTest {

    @Test
    @Timeout(5)
    @DisplayName("순환이 든 조직도에서 조상을 물으면 끝없이 돌지 않고 경로를 담아 즉시 던진다")
    void 순환이면_조상_질의가_즉시_실패한다() {
        // given — A 가 B 를, B 가 A 를 하위로 갖는다. 순환 시나리오(L16/S16)가 만드는 모양이다
        var chart = new OrgChart(new DirectorySnapshot(Map.of(), Map.of(
                "A", new DirectoryGroup("A", null, "A", Set.of(MemberRef.group("B"))),
                "B", new DirectoryGroup("B", null, "B", Set.of(MemberRef.group("A"))))), null);

        // when, then — 가드가 없으면 10분 타임아웃이 되고, 원인이 보이지 않는다
        assertThatThrownBy(() -> chart.조상들("A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환")
                .hasMessageContaining("A → B → A");
    }

    @Test
    @DisplayName("아이디를 바꾼 조직도는 바뀐 아이디의 원래 조직도 아이디를 알고, 모르는 아이디는 그대로 돌려준다")
    void 바꾼_조직도는_원래_아이디를_안다() {
        // given
        var 조직도 = 작은_조직도();

        // when
        var 바뀐 = 조직도.아이디를_바꾼다(id -> "x:" + id);

        // then
        assertThat(바뀐.원래아이디("x:kim")).isEqualTo("kim");
        assertThat(바뀐.원래아이디("x:DEV")).isEqualTo("DEV");
        assertThat(바뀐.원래아이디("x:land")).as("랜드마크 아이디도 안다").isEqualTo("land");
        assertThat(바뀐.원래아이디("모르는")).isEqualTo("모르는");
    }

    @Test
    @DisplayName("아이디를 두 번 바꿔도 맨 처음 조직도 아이디를 돌려준다")
    void 두_번_바꿔도_처음_아이디를_안다() {
        // given
        var 한번 = 작은_조직도().아이디를_바꾼다(id -> "x:" + id);

        // when
        var 두번 = 한번.아이디를_바꾼다(id -> "y:" + id);

        // then
        assertThat(두번.원래아이디("y:x:kim")).isEqualTo("kim");
        assertThat(두번.원래아이디("y:x:DEV")).isEqualTo("DEV");
    }

    @Test
    @DisplayName("아이디를 바꾸지 않은 조직도는 받은 아이디를 그대로 돌려준다")
    void 바꾸지_않은_조직도는_아이디를_그대로_돌려준다() {
        // given, when
        var 조직도 = 작은_조직도();

        // then
        assertThat(조직도.원래아이디("kim")).isEqualTo("kim");
    }

    private static OrgChart 작은_조직도() {
        var 랜드마크 = new Landmarks("land", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-",
                "-", "-", "-", "-", "-", List.of());
        return new OrgChart(new DirectorySnapshot(
                Map.of("kim", new DirectoryUser("kim", null, "kim", "김", null, true)),
                Map.of("DEV", new DirectoryGroup("DEV", null, "개발", Set.of(MemberRef.user("kim"))))), 랜드마크);
    }
}

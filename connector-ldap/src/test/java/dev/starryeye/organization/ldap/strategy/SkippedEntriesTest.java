package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkippedEntriesTest {

    @Test
    @DisplayName("건너뛴 것을 사유별 건수와 예시로 한 줄에 담는다 — 예시는 사유마다 다섯 개까지")
    void 요약은_사유별_건수와_예시다() {
        // given
        var 건너뜀 = new SkippedEntries("직원", "employeeNumber");
        IntStream.range(0, 7).forEach(i -> 건너뜀.기록한다(SkippedEntries.사유.식별_속성_없음, "dn='uid=svc" + i + "'"));
        건너뜀.기록한다(SkippedEntries.사유.아이디_겹침, "kim(건너뛴 dn='uid=kim*', 유지된 dn='uid=kim ')");

        // when
        String 요약 = 건너뜀.요약();

        // then
        assertThat(요약).contains("직원 검색").contains("8건")
                .contains("식별 속성 'employeeNumber' 이 없거나 비었음 7건")
                .contains("uid=svc4").doesNotContain("uid=svc5")
                .contains("외 2건")
                .contains("아이디 겹침 1건").contains("kim(건너뛴 dn='uid=kim*', 유지된 dn='uid=kim ')");
        assertThat(요약).as("한 줄이다").doesNotContain("\n");
    }

    @Test
    @DisplayName("사유별 건수는 기록한 만큼이고 기록하지 않은 사유는 0 이다")
    void 사유별_건수를_센다() {
        // given
        var 건너뜀 = new SkippedEntries("조직", "cn");
        건너뜀.기록한다(SkippedEntries.사유.식별_속성_없음, "dn='ou=a'");
        건너뜀.기록한다(SkippedEntries.사유.식별_속성_없음, "dn='ou=b'");

        // when, then
        assertThat(건너뜀.건수(SkippedEntries.사유.식별_속성_없음)).isEqualTo(2);
        assertThat(건너뜀.건수(SkippedEntries.사유.아이디_겹침)).isZero();
        assertThat(건너뜀.건수(SkippedEntries.사유.부모_조직_없음)).isZero();
    }

    @Test
    @DisplayName("겹침은 건너뛴 dn 과 유지된 dn 을 한 문구로 기록한다 — 경고를 읽는 쪽이 어느 쪽이 사라졌는지 안다")
    void 겹침은_두_dn을_함께_기록한다() {
        // given
        var 건너뜀 = new SkippedEntries("직원", "uid");

        // when
        건너뜀.겹침을_기록한다("kim_lee", "uid=kim*lee,ou=people", "uid=kim lee,ou=people");

        // then
        assertThat(건너뜀.건수(SkippedEntries.사유.아이디_겹침)).isEqualTo(1);
        assertThat(건너뜀.요약()).contains("아이디 겹침 1건")
                .contains("kim_lee(건너뛴 dn='uid=kim*lee,ou=people', 유지된 dn='uid=kim lee,ou=people')");
    }

    @Test
    @DisplayName("member 값은 식별 속성이 없는 별개의 집계다 — 읽은 직원도 조직도 아닌 값을 건수와 예시로 한 줄에 담는다")
    void member_값_요약은_검색이_아니라_값을_센다() {
        // given
        var 건너뜀 = SkippedEntries.member값();
        IntStream.range(0, 7).forEach(i -> 건너뜀.기록한다(SkippedEntries.사유.읽지_않은_member,
                "조직 'DEV'(dn='cn=DEV') 의 member 'uid=svc" + i + "'"));

        // when
        String 요약 = 건너뜀.요약();

        // then
        assertThat(요약).startsWith("member 값 7건을 건너뛰었다")
                .contains("직원도 조직도 아님").contains("컴퓨터").contains("연락처")
                .contains("uid=svc4").doesNotContain("uid=svc5").contains("외 2건")
                .doesNotContain("검색").doesNotContain("null");
    }

    @Test
    @DisplayName("받은 엔트리가 있는데 하나도 남지 않으면 데이터 오류다 — 권한이 0 이 되는 회차를 성공으로 끝내지 않는다")
    void 아무도_남지_않으면_멈춘다() {
        // given
        var 건너뜀 = new SkippedEntries("직원", "employeeNumber");
        건너뜀.기록한다(SkippedEntries.사유.식별_속성_없음, "dn='uid=a'");

        // when, then
        assertThatThrownBy(() -> 건너뜀.아무도_남지_않으면_멈춘다(1, 0))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("직원 검색").hasMessageContaining("employeeNumber");
    }

    @Test
    @DisplayName("일부만 남거나 받은 것이 없으면 멈추지 않는다 — 일부 누락은 삭제 가드가, 빈 검색은 다른 가드가 본다")
    void 일부만_빠지면_멈추지_않는다() {
        // given
        var 건너뜀 = new SkippedEntries("직원", "employeeNumber");

        // when, then
        assertThatCode(() -> {
            건너뜀.아무도_남지_않으면_멈춘다(10, 9);
            건너뜀.아무도_남지_않으면_멈춘다(0, 0);
        }).doesNotThrowAnyException();
    }
}

package dev.starryeye.organization.core.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 강제 회차의 허용 수 — 1 이상만 만들 수 있고, 결론 앞에 붙일 문구를 준다(설계 2026-11-20 F1·F3·F4). */
class ForceAllowanceTest {

    // 깨뜨릴 변경: 컴팩트 생성자의 검사를 지우거나 maxDeletes < 0 으로 바꾸기
    @ParameterizedTest(name = "maxDeletes={0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("허용 수가 1 미만이면 만들 수 없다 — 설정의 0 은 '끔'이라 요청의 0 을 받으면 같은 수가 두 뜻이 된다")
    void 허용_수가_1_미만이면_만들_수_없다(int 허용_수) {
        // when, then
        assertThatThrownBy(() -> new ForceAllowance(허용_수)).isInstanceOf(IllegalArgumentException.class);
    }

    // 깨뜨릴 변경: 검사를 maxDeletes <= 1 로 바꾸기
    @Test
    @DisplayName("허용 수 1 은 만들 수 있다")
    void 허용_수_1_은_만들_수_있다() {
        // when, then
        assertThatCode(() -> new ForceAllowance(1)).doesNotThrowAnyException();
    }

    // 깨뜨릴 변경: 문구를 바꾸거나 숫자에 쉼표를 넣기
    @Test
    @DisplayName("결론 앞 문구는 허용한 지울 줄 수를 쉼표 없이 적는다")
    void 결론_앞_문구는_허용_수를_쉼표_없이_적는다() {
        // when, then
        assertThat(new ForceAllowance(12500).note()).isEqualTo("강제 실행 — 지울 줄 허용 12500건");
    }

    // 깨뜨릴 변경: 둘 다 null 을 허용하거나 maxReactivations 검사를 지우기
    @Test
    @DisplayName("두 수 가운데 하나도 없거나 준 수가 1 미만이면 만들 수 없다(설계 2026-11-24 R9)")
    void 두_수가_없거나_1_미만이면_만들_수_없다() {
        // when, then
        assertThatThrownBy(() -> new ForceAllowance(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForceAllowance(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForceAllowance(0, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    // 깨뜨릴 변경: note 의 세 꼴 가운데 하나를 바꾸기
    @Test
    @DisplayName("결론 앞 문구는 준 수에 따라 세 꼴이다(설계 2026-11-24 R11)")
    void 결론_앞_문구는_세_꼴이다() {
        // when, then
        assertThat(new ForceAllowance(null, 7).note()).isEqualTo("강제 실행 — 재활성 허용 7명");
        assertThat(new ForceAllowance(12500, 7).note()).isEqualTo("강제 실행 — 지울 줄 허용 12500건·재활성 허용 7명");
    }

    // 깨뜨릴 변경: 보조 생성자를 지우거나 다른 값을 넣기
    @Test
    @DisplayName("수 하나짜리 보조 생성자는 재활성 수가 없는 허용 수와 같다")
    void 보조_생성자는_재활성_수가_없는_허용_수와_같다() {
        // when, then
        assertThat(new ForceAllowance(5)).isEqualTo(new ForceAllowance(5, null));
    }
}

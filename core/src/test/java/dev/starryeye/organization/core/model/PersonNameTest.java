package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNameTest {

    @Test
    @DisplayName("빈 문자열은 없음으로 본다 — 여섯 칸이 모두 비면 EMPTY 와 같다")
    void 빈_문자열은_없음이다() {
        // when
        PersonName name = new PersonName("", "", "", "", "", "");

        // then
        assertThat(name).isEqualTo(PersonName.EMPTY);
        assertThat(new PersonName(null, "홍", "", null, null, null).givenName()).isNull();
    }

    @Test
    @DisplayName("한 칸이라도 있으면 비어 있지 않다")
    void 한_칸이라도_있으면_비어_있지_않다() {
        // when
        PersonName name = PersonName.EMPTY.withGivenName("길동");

        // then
        assertThat(name).isNotEqualTo(PersonName.EMPTY);
        assertThat(name.givenName()).isEqualTo("길동");
    }

    @Test
    @DisplayName("직원의 이름이 null 이면 EMPTY 이고, 6인자 생성자는 이름 없는 직원이다")
    void 직원의_이름_기본값() {
        // when
        DirectoryUser 여섯 = new DirectoryUser("kim", null, "kim", "김철수", null, true);
        DirectoryUser 널 = new DirectoryUser("kim", null, "kim", "김철수", null, true, null);

        // then
        assertThat(여섯.name()).isEqualTo(PersonName.EMPTY);
        assertThat(널).isEqualTo(여섯);
    }

    @Test
    @DisplayName("with 로 한 칸만 바꾼 복사는 이름을 지킨다")
    void with_복사는_이름을_지킨다() {
        // given
        PersonName 이름 = new PersonName(null, "홍", "길동", null, null, null);
        DirectoryUser 직원 = new DirectoryUser("hong", null, "hong", "홍길동", null, true, 이름);

        // when, then
        assertThat(직원.withActive(false).name()).isEqualTo(이름);
        assertThat(직원.withId("hong2").name()).isEqualTo(이름);
    }
}

package dev.starryeye.organization.core.tuple;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdNormalizerTest {

    @Test
    @DisplayName("영문·숫자·허용기호로만 이루어진 식별자는 그대로 유지된다")
    void 정상_식별자는_그대로_유지된다() {
        // given
        String raw = "DEV-001.kim_2@example.com";

        // when
        String normalized = IdNormalizer.normalize(raw);

        // then
        assertThat(normalized).isEqualTo("DEV-001.kim_2@example.com");
    }

    @Test
    @DisplayName("한글 조직코드는 훼손되지 않고 그대로 유지된다")
    void 한글_식별자는_보존된다() {
        // given
        String raw = "개발본부";

        // when
        String normalized = IdNormalizer.normalize(raw);

        // then
        assertThat(normalized).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("OpenFGA 파싱을 깨는 문자만 밑줄로 치환된다")
    void 금지문자만_치환된다() {
        // given
        String raw = "cn=김철수,ou=백엔드 팀:1#a*b";

        // when
        String normalized = IdNormalizer.normalize(raw);

        // then
        assertThat(normalized).isEqualTo("cn=김철수_ou=백엔드_팀_1_a_b");
    }

    @Test
    @DisplayName("서로 다른 한글 조직코드가 같은 식별자로 뭉개지지 않는다")
    void 서로_다른_한글_조직코드는_충돌하지_않는다() {
        // given
        String 개발본부 = "개발본부";
        String 영업본부 = "영업본부";

        // when
        String a = IdNormalizer.normalize(개발본부);
        String b = IdNormalizer.normalize(영업본부);

        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("비어 있는 식별자는 예외를 던진다")
    void 빈_식별자는_예외를_던진다() {
        // given
        String raw = "   ";

        // when, then
        assertThatThrownBy(() -> IdNormalizer.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("식별자");
    }

    @Test
    @DisplayName("파이프는 정규화로 걸러진다 — 스냅샷 정렬키의 구분자이기 때문이다")
    void 파이프는_걸러진다() {
        // given — Keys.tupleSk 가 user|relation|object 로 잇고 parseTupleSk 가 그대로 되읽는다.
        // user 값에 파이프가 남으면 되읽을 때 경계가 밀려 전혀 다른 튜플이 된다.
        String raw = "a|b";

        // when
        String normalized = IdNormalizer.normalize(raw);

        // then
        assertThat(normalized).isEqualTo("a_b");
        assertThat(normalized).doesNotContain("|");
    }
}

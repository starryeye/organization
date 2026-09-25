package dev.starryeye.organization.scim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimFilterTest {

    private static ScimFilter 파싱(String filter) {
        return ScimFilter.parse(filter, ScimResourceType.USER);
    }

    private static void 거절한다(String filter) {
        assertThatThrownBy(() -> 파싱(filter))
                .as(filter)
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("invalidFilter");
                });
    }

    @Test
    @DisplayName("속성 eq 문자열 하나를 읽는다 — 속성 이름은 소문자로 돌려준다")
    void eq_하나를_읽는다() {
        // when
        ScimFilter filter = 파싱("userName eq \"kim\"");

        // then
        assertThat(filter.terms()).containsExactly(new ScimFilter.Term("username", "kim"));
    }

    @Test
    @DisplayName("연산자와 속성 이름은 대소문자를 가리지 않는다")
    void 연산자와_속성_이름은_대소문자를_가리지_않는다() {
        // when
        ScimFilter filter = 파싱("USERNAME EQ \"kim\" AND Active Eq true");

        // then
        assertThat(filter.terms()).containsExactly(
                new ScimFilter.Term("username", "kim"),
                new ScimFilter.Term("active", Boolean.TRUE));
    }

    @Test
    @DisplayName("리소스의 core 스키마 URN 이 붙은 속성 이름을 받는다")
    void URN_이_붙은_이름을_받는다() {
        // when
        ScimFilter filter = 파싱("urn:ietf:params:scim:schemas:core:2.0:User:userName eq \"kim\"");

        // then
        assertThat(filter.terms()).containsExactly(new ScimFilter.Term("username", "kim"));
    }

    @Test
    @DisplayName("문자열의 이스케이프를 풀고 한글은 그대로 둔다")
    void 이스케이프를_풀고_한글은_그대로_둔다() {
        // when, then
        assertThat(파싱("userName eq \"a\\\"b\\\\c\\u0041\"").terms().get(0).value()).isEqualTo("a\"b\\cA");
        assertThat(파싱("displayName eq \"홍길동\"").terms().get(0).value()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("and 로 여러 조건을 잇는다")
    void and_로_잇는다() {
        // when
        ScimFilter filter = 파싱("userName eq \"kim\" and externalId eq \"e1\" and active eq false");

        // then
        assertThat(filter.terms()).containsExactly(
                new ScimFilter.Term("username", "kim"),
                new ScimFilter.Term("externalid", "e1"),
                new ScimFilter.Term("active", Boolean.FALSE));
    }

    @Test
    @DisplayName("or·not·괄호는 받지 않는다")
    void or_not_괄호는_받지_않는다() {
        거절한다("userName eq \"a\" or userName eq \"b\"");
        거절한다("not (userName eq \"a\")");
        거절한다("(userName eq \"a\")");
    }

    @Test
    @DisplayName("eq 가 아닌 연산자는 받지 않는다")
    void eq_가_아닌_연산자는_받지_않는다() {
        거절한다("userName ne \"a\"");
        거절한다("userName co \"a\"");
        거절한다("userName sw \"a\"");
        거절한다("userName pr");
        거절한다("meta.lastModified gt \"2026-01-01T00:00:00Z\"");
    }

    @Test
    @DisplayName("대괄호 값 경로와 하위 속성은 받지 않는다")
    void 값_경로와_하위_속성은_받지_않는다() {
        거절한다("emails[type eq \"work\"].value eq \"a@b.c\"");
        거절한다("name.familyName eq \"김\"");
    }

    @Test
    @DisplayName("문자열·true·false 가 아닌 값은 받지 않는다")
    void 문자열과_불리언이_아닌_값은_받지_않는다() {
        거절한다("userName eq kim");
        거절한다("userName eq 1");
        거절한다("userName eq null");
    }

    @Test
    @DisplayName("true·false 는 JSON 처럼 소문자만 받는다")
    void 불리언은_소문자만_받는다() {
        거절한다("active eq TRUE");
        거절한다("active eq True");
        거절한다("active eq FALSE");
    }

    @Test
    @DisplayName("공백 두 칸, 끝나지 않은 and, 닫히지 않은 따옴표, 빈 필터는 받지 않는다")
    void 문법이_어긋나면_받지_않는다() {
        거절한다("userName  eq \"a\"");
        거절한다("userName eq \"a\" and");
        거절한다("userName eq \"a");
        거절한다("userName eq \"a\" ");
        거절한다("");
    }

    @Test
    @DisplayName("다른 리소스의 스키마 URN 이 붙은 이름은 받지 않는다")
    void 다른_스키마_URN_은_받지_않는다() {
        거절한다("urn:ietf:params:scim:schemas:core:2.0:Group:displayName eq \"a\"");
    }

    @Test
    @DisplayName("주어진 속성 순서대로 처음 나오는 조건을 고른다 — 없으면 빈 값이다")
    void 우선순위대로_조건을_고른다() {
        // given
        ScimFilter filter = 파싱("externalId eq \"e1\" and userName eq \"kim\"");

        // when, then
        assertThat(filter.first(List.of("id", "username", "externalid")))
                .contains(new ScimFilter.Term("username", "kim"));
        assertThat(filter.first(List.of("id"))).isEmpty();
    }
}

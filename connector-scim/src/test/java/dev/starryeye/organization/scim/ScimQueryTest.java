package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimSearchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimQueryTest {

    private static ScimQuery 조회(Long startIndex, Long count) {
        return ScimQuery.of(ScimResourceType.USER, null, startIndex, count, null, null, null, null);
    }

    private static void 값_오류(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ScimException.class,
                e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
    }

    @Test
    @DisplayName("아무것도 없으면 1번부터 100건, 오름차순, 필터 없음이다")
    void 기본값() {
        // when
        ScimQuery query = 조회(null, null);

        // then
        assertThat(query.filter()).isNull();
        assertThat(query.startIndex()).isEqualTo(1);
        assertThat(query.count()).isEqualTo(100);
        assertThat(query.descending()).isFalse();
    }

    @Test
    @DisplayName("startIndex 가 1 미만이면 1, count 가 음수면 0, 100 을 넘으면 100 이다 (RFC 7644 §3.4.2.4)")
    void 페이지_경계() {
        assertThat(조회(0L, null).startIndex()).isEqualTo(1);
        assertThat(조회(-5L, null).startIndex()).isEqualTo(1);
        assertThat(조회(null, -1L).count()).isZero();
        assertThat(조회(null, 0L).count()).isZero();
        assertThat(조회(null, 101L).count()).isEqualTo(100);
    }

    @Test
    @DisplayName("URL 의 startIndex·count 가 정수가 아니면 invalidValue 다")
    void 정수가_아니면_거절한다() {
        값_오류(() -> ScimQuery.fromRequest(ScimResourceType.USER,
                MockServerRequest.builder().queryParam("startIndex", "abc").build()));
        값_오류(() -> ScimQuery.fromRequest(ScimResourceType.USER,
                MockServerRequest.builder().queryParam("count", "1.5").build()));
    }

    @Test
    @DisplayName("URL 파라미터를 읽는다 — 필터, 페이지, 내림차순, 속성 선택")
    void URL_파라미터를_읽는다() {
        // when
        ScimQuery query = ScimQuery.fromRequest(ScimResourceType.USER, MockServerRequest.builder()
                .queryParam("filter", "userName eq \"kim\"")
                .queryParam("startIndex", "11")
                .queryParam("count", "5")
                .queryParam("sortBy", "userName")
                .queryParam("sortOrder", "DESCENDING")
                .queryParam("attributes", "userName")
                .build());

        // then
        assertThat(query.filter().terms()).containsExactly(new ScimFilter.Term("username", "kim"));
        assertThat(query.startIndex()).isEqualTo(11);
        assertThat(query.count()).isEqualTo(5);
        assertThat(query.descending()).isTrue();
        assertThat(query.projection().includes("displayName")).isFalse();
    }

    @Test
    @DisplayName("정렬은 인덱스 키만, 방향은 ascending·descending 만 받는다")
    void 정렬_규칙() {
        assertThat(ScimQuery.of(ScimResourceType.USER, null, null, null,
                "urn:ietf:params:scim:schemas:core:2.0:User:userName", "ascending", null, null).descending()).isFalse();
        assertThat(ScimQuery.of(ScimResourceType.GROUP, null, null, null,
                "displayName", "descending", null, null).descending()).isTrue();
        값_오류(() -> ScimQuery.of(ScimResourceType.USER, null, null, null, "displayName", null, null, null));
        값_오류(() -> ScimQuery.of(ScimResourceType.USER, null, null, null, null, "up", null, null));
    }

    @Test
    @DisplayName("빈 필터는 invalidFilter 다")
    void 빈_필터는_거절한다() {
        assertThatThrownBy(() -> ScimQuery.of(ScimResourceType.USER, "", null, null, null, null, null, null))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidFilter"));
    }

    @Test
    @DisplayName(".search 본문을 같은 조회로 바꾸고, SearchRequest 스키마가 없으면 invalidSyntax 다")
    void search_본문을_바꾼다() {
        // given
        var body = new ScimSearchRequest(List.of(ScimSchemas.SEARCH_REQUEST), List.of("userName"), null,
                "userName eq \"kim\"", null, null, 1L, 10L);
        var 스키마없음 = new ScimSearchRequest(null, null, null, null, null, null, null, null);

        // when
        ScimQuery query = ScimQuery.fromSearch(ScimResourceType.USER, body);

        // then
        assertThat(query.count()).isEqualTo(10);
        assertThat(query.filter().terms()).hasSize(1);
        assertThatThrownBy(() -> ScimQuery.fromSearch(ScimResourceType.USER, 스키마없음))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidSyntax"));
    }
}

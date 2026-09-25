package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimUserListingTest {

    private FakeStateRepository state;
    private FakeQueryRepository query;
    private FakePageBookmarkRepository bookmarks;
    private ScimUserListing listing;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        query = new FakeQueryRepository(state);
        bookmarks = new FakePageBookmarkRepository();
        listing = new ScimUserListing(state, query, bookmarks);
    }

    /** u000 … u(n-1). 아이디와 userName 이 같고 externalId 는 ext-i 다. */
    private void 직원들을_둔다(int n) {
        for (int i = 0; i < n; i++) {
            String id = "u%03d".formatted(i);
            state.saveUser(new DirectoryUser(id, "ext-" + i, id, "직원 " + i, null, true)).block();
        }
    }

    private void 직원(String id, String userName, String externalId, boolean active) {
        state.saveUser(new DirectoryUser(id, externalId, userName, "직원 " + id, null, active)).block();
    }

    private ScimListResponse 조회(String filter, long startIndex, long count) {
        return listing.list(ScimQuery.of(ScimResourceType.USER, filter, startIndex, count,
                null, null, null, null)).block();
    }

    private static List<String> 아이디들(ScimListResponse response) {
        return response.resources().stream().map(node -> node.get("id").asText()).toList();
    }

    private static void 필터_오류(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ScimException.class,
                e -> assertThat(e.getScimType()).isEqualTo("invalidFilter"));
    }

    @Test
    @DisplayName("필터 없는 첫 페이지는 전체를 세고 다음 페이지의 책갈피를 남긴다")
    void 첫_페이지는_세고_책갈피를_남긴다() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 1, 100);

        // then
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(page.itemsPerPage()).isEqualTo(100);
        assertThat(아이디들(page)).startsWith("u000").endsWith("u099").hasSize(100);
        assertThat(bookmarks.saved).containsKey(FakePageBookmarkRepository.key(ListingKind.USER, false, 101));
        assertThat(bookmarks.saved.get(FakePageBookmarkRepository.key(ListingKind.USER, false, 101)).totalResults())
                .isEqualTo(250);
    }

    @Test
    @DisplayName("책갈피가 있으면 세지도 건너뛰지도 않고 그 자리부터 이어 읽는다")
    void 책갈피로_이어_읽는다() {
        // given
        직원들을_둔다(250);
        조회(null, 1, 100);
        query.calls.clear();

        // when
        ScimListResponse page = 조회(null, 101, 100);

        // then
        assertThat(아이디들(page)).startsWith("u100").endsWith("u199");
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(query.calls).containsExactly("listUsers:100:100");
    }

    @Test
    @DisplayName("책갈피가 없는 중간 페이지는 앞을 건너뛰고 세어서 정확한 페이지를 준다")
    void 책갈피가_없으면_건너뛴다() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 101, 100);

        // then
        assertThat(아이디들(page)).startsWith("u100").endsWith("u199");
        assertThat(query.calls).contains("skipUsers:100", "countUsers");
    }

    @Test
    @DisplayName("마지막 페이지는 남은 만큼만 주고 책갈피를 더 남기지 않는다")
    void 마지막_페이지() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 201, 100);

        // then
        assertThat(page.itemsPerPage()).isEqualTo(50);
        assertThat(bookmarks.saved).doesNotContainKey(FakePageBookmarkRepository.key(ListingKind.USER, false, 251));
    }

    @Test
    @DisplayName("startIndex 가 전체보다 크면 빈 Resources 와 전체 수를 준다")
    void 전체보다_큰_startIndex() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 1000, 100);

        // then
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(page.resources()).isEmpty();
    }

    @Test
    @DisplayName("count=0 이면 Resources 없이 전체 수만 준다")
    void count_0_은_전체_수만() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = 조회(null, 1, 0);

        // then
        assertThat(page.totalResults()).isEqualTo(250);
        assertThat(page.resources()).isNull();
        assertThat(query.calls).doesNotContain("listUsers:null:0");
    }

    @Test
    @DisplayName("내림차순이면 역순으로 준다")
    void 내림차순() {
        // given
        직원들을_둔다(250);

        // when
        ScimListResponse page = listing.list(ScimQuery.of(ScimResourceType.USER, null, 1L, 3L,
                "userName", "descending", null, null)).block();

        // then
        assertThat(아이디들(page)).containsExactly("u249", "u248", "u247");
    }

    @Test
    @DisplayName("userName eq 는 대소문자를 가리지 않는다")
    void userName_eq_는_대소문자를_가리지_않는다() {
        // given
        직원("Kim.Lee", "Kim.Lee", "ext-kim", true);
        직원("park", "park", "ext-park", true);

        // when
        ScimListResponse page = 조회("userName eq \"KIM.LEE\"", 1, 100);

        // then
        assertThat(page.totalResults()).isEqualTo(1);
        assertThat(page.resources().get(0).get("userName").asText()).isEqualTo("Kim.Lee");
    }

    @Test
    @DisplayName("대소문자만 다른 userName 둘이 있으면 둘 다 id 순으로 준다")
    void 대소문자만_다른_둘() {
        // given — GSI 최종 일관성 창이나 이전 데이터로 생길 수 있는 상태
        직원("Kim", "Kim", "e1", true);
        직원("kim", "kim", "e2", true);

        // when
        ScimListResponse page = 조회("userName eq \"kim\"", 1, 100);

        // then
        assertThat(아이디들(page)).containsExactly("Kim", "kim");
    }

    @Test
    @DisplayName("and 뒤의 조건은 찾은 후보 위에서 확인한다")
    void and_뒤의_조건을_확인한다() {
        // given
        직원("kim", "kim", "e1", true);

        // when, then
        assertThat(조회("userName eq \"kim\" and active eq true", 1, 100).totalResults()).isEqualTo(1);
        assertThat(조회("userName eq \"kim\" and active eq false", 1, 100).totalResults()).isZero();
        assertThat(조회("userName eq \"kim\" and displayName eq \"직원 KIM\"", 1, 100).totalResults()).isEqualTo(1);
    }

    @Test
    @DisplayName("externalId 는 대소문자를 가리고, id 로도 찾는다")
    void externalId_와_id() {
        // given
        직원("u1", "u1", "EXT-1", true);

        // when, then
        assertThat(조회("externalId eq \"ext-1\"", 1, 100).totalResults()).isZero();
        assertThat(조회("externalId eq \"EXT-1\"", 1, 100).totalResults()).isEqualTo(1);
        assertThat(조회("id eq \"u1\"", 1, 100).totalResults()).isEqualTo(1);
    }

    @Test
    @DisplayName("인덱스로 찾을 조건이 없거나, 모르는 속성이거나, 값의 타입이 틀리면 invalidFilter 다")
    void 받지_않는_필터() {
        필터_오류(() -> 조회("displayName eq \"x\"", 1, 100));
        필터_오류(() -> 조회("active eq true", 1, 100));
        필터_오류(() -> 조회("nickName eq \"x\"", 1, 100));
        필터_오류(() -> 조회("userName eq \"kim\" and active eq \"true\"", 1, 100));
        필터_오류(() -> 조회("userName eq true", 1, 100));
    }

    @Test
    @DisplayName("속성 선택을 목록의 각 리소스에 적용한다")
    void 속성_선택을_적용한다() {
        // given
        직원("kim", "kim", "e1", true);

        // when
        ScimListResponse page = listing.list(ScimQuery.of(ScimResourceType.USER, "userName eq \"kim\"", 1L, 100L,
                null, null, List.of("userName"), null)).block();

        // then
        JsonNode resource = page.resources().get(0);
        assertThat(resource.has("userName")).isTrue();
        assertThat(resource.has("displayName")).isFalse();
    }
}

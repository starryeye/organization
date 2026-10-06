package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScimListHandlerTest {

    private FakeStateRepository state;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        var writer = new FakeTupleWriter();
        var checker = new FakeTupleChecker();
        var lock = new FakeMutationLock();
        var useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(state, query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();

        // setUp 의 끝 — client 를 만든 다음
        state.saveUser(new DirectoryUser("Kim.Lee", "ext-kim", "Kim.Lee", "이김", "kim@example.com", true)).block();
        state.saveUser(new DirectoryUser("park", "ext-park", "park", "박", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "grp-dev", "Dev Team", Set.of(MemberRef.user("Kim.Lee")))).block();
        state.findGroupCalls.clear();
    }

    @Test
    @DisplayName("Okta 의 필터 조회에 ListResponse 로 답한다")
    void Okta_필터_조회() {
        client.get().uri("/scim/v2/Users?filter={f}&startIndex=1&count=100", "userName eq \"kim.lee\"")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.LIST_RESPONSE)
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.startIndex").isEqualTo(1)
                .jsonPath("$.itemsPerPage").isEqualTo(1)
                .jsonPath("$.Resources[0].userName").isEqualTo("Kim.Lee");
    }

    @Test
    @DisplayName("결과가 없으면 빈 Resources, count=0 이면 Resources 없이 전체 수만 준다")
    void 결과_없음과_count_0() {
        client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"nobody\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(0)
                .jsonPath("$.Resources").isArray()
                .jsonPath("$.Resources").isEmpty();

        client.get().uri("/scim/v2/Users?count=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources").doesNotExist();
    }

    @Test
    @DisplayName("Entra 의 조직 조회 — excludedAttributes=members 면 멤버를 읽지도 담지도 않는다")
    void Entra_조직_조회() {
        client.get().uri("/scim/v2/Groups?excludedAttributes=members&filter={f}", "displayName eq \"Dev Team\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].id").isEqualTo("DEV001")
                .jsonPath("$.Resources[0].members").doesNotExist();

        client.get().uri("/scim/v2/Groups/DEV001?excludedAttributes=members")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("DEV001")
                .jsonPath("$.members").doesNotExist();

        assertThat(state.findGroupCalls).isEmpty();
    }

    @Test
    @DisplayName("멤버를 싣는 조직 목록은 흘려 쓴다 — Content-Length 가 없고 itemsPerPage 가 실제 수다(점검 P5)")
    void 멤버를_싣는_목록은_흘려_쓴다() {
        // when, then
        client.get().uri("/scim/v2/Groups").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
                .expectBody()
                .jsonPath("$.itemsPerPage").isEqualTo(1)
                .jsonPath("$.Resources[0].id").isEqualTo("DEV001")
                .jsonPath("$.Resources[0].members[0].value").isEqualTo("Kim.Lee");
        assertThat(state.findGroupCalls).isEmpty();
    }

    @Test
    @DisplayName("조직 .search 도 멤버를 싣고 필터가 있으면 흘려 쓴다 — 필터로 고른 조직의 멤버 줄만 읽는다")
    void 멤버를_싣는_search_는_흘려_쓴다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV002", "grp-dev2", "Backend", Set.of(MemberRef.user("park")))).block();
        state.findGroupCalls.clear();
        state.findMemberRefsCalls.clear();

        // when, then
        client.post().uri("/scim/v2/Groups/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"],
                         "filter":"displayName eq \\"Backend\\"","startIndex":1,"count":10}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.itemsPerPage").isEqualTo(1)
                .jsonPath("$.Resources[0].id").isEqualTo("DEV002")
                .jsonPath("$.Resources[0].members[0].value").isEqualTo("park");
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findMemberRefsCalls).containsExactly("DEV002");
    }

    @Test
    @DisplayName("count=0 인 조직 목록은 멤버가 응답에 남아도 Resources 없이 전체 수만 주고 멤버 줄을 읽지 않는다")
    void count_0_이면_멤버_줄을_읽지_않는다() {
        // given
        state.findMemberRefsCalls.clear();

        // when, then
        client.get().uri("/scim/v2/Groups?count=0").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.Resources").doesNotExist();
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findMemberRefsCalls).isEmpty();
    }

    @Test
    @DisplayName("조직 쓰기 응답은 members 를 실을 때만 멤버 줄을 읽고, 어느 쪽도 findGroup 으로 모으지 않는다")
    void 쓰기_응답도_members_를_빼면_읽지_않는다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"%s","displayName":"%s",
                 "members":[{"value":"park","type":"User"}]}
                """;

        // when — 같은 모양의 조직 둘을 만들되 하나만 members 를 뺀다
        state.findGroupCalls.clear();
        state.findMemberRefsCalls.clear();
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.formatted("grp-a", "A"))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.members[0].value").isEqualTo("park");
        int 멤버포함_조직읽기 = state.findGroupCalls.size();
        int 멤버포함_멤버줄읽기 = state.findMemberRefsCalls.size();

        state.findGroupCalls.clear();
        state.findMemberRefsCalls.clear();
        client.post().uri("/scim/v2/Groups?excludedAttributes=members").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.formatted("grp-b", "B"))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.members").doesNotExist();
        int 멤버제외_조직읽기 = state.findGroupCalls.size();
        int 멤버제외_멤버줄읽기 = state.findMemberRefsCalls.size();

        // then — 응답을 그리려 멤버 줄을 이어 읽던 한 번만 빠지고, 응답은 어느 쪽도 findGroup 으로 멤버를 모으지 않는다
        assertThat(멤버제외_멤버줄읽기).isEqualTo(멤버포함_멤버줄읽기 - 1);
        assertThat(멤버제외_조직읽기).isEqualTo(멤버포함_조직읽기);
    }

    @Test
    @DisplayName("단건 GET 과 쓰기 응답에도 attributes 를 적용한다")
    void 단건과_쓰기_응답의_속성_선택() {
        // given, when, then — 단건 GET (park 은 setUp 에서 심었다)
        client.get().uri("/scim/v2/Users/park?attributes=userName")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("park")
                .jsonPath("$.userName").isEqualTo("park")
                .jsonPath("$.displayName").doesNotExist();

        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"choi","active":true}
                """;

        // when
        var 응답 = client.post().uri("/scim/v2/Users?attributes=id")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();

        // then
        응답.expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").value(id -> {
                    assertThat(UUID.fromString((String) id)).hasToString((String) id);
                    assertThat(state.users.get((String) id).userName()).isEqualTo("choi");
                })
                .jsonPath("$.userName").doesNotExist();
    }

    @Test
    @DisplayName("잘못된 속성 선택은 쓰기 전에 거절한다 — 상태가 바뀌지 않는다")
    void 잘못된_속성_선택은_쓰기_전에_거절한다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"choi","active":true}
                """;

        // when
        var 응답 = client.post().uri("/scim/v2/Users?attributes=nickName")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();

        // then
        응답.expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");
        assertThat(state.users.values()).extracting(DirectoryUser::userName).doesNotContain("choi");
    }

    @Test
    @DisplayName("받지 않는 필터·정렬·페이지 값은 400 과 scimType 으로 답한다")
    void 오류_응답() {
        client.get().uri("/scim/v2/Users?filter={f}", "userName co \"k\"")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidFilter");
        client.get().uri("/scim/v2/Users?sortBy=displayName")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidValue");
        client.get().uri("/scim/v2/Users?startIndex=abc")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidValue");
        client.get().uri("/scim/v2/Users?attributes=userName&excludedAttributes=emails")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidValue");
    }

    @Test
    @DisplayName(".search 는 본문의 조회를 같은 엔진으로 실행한다 — SearchRequest 스키마가 없으면 invalidSyntax")
    void search() {
        client.post().uri("/scim/v2/Users/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"],
                         "filter":"userName eq \\"park\\"","attributes":["userName"],"startIndex":1,"count":10}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.Resources[0].userName").isEqualTo("park")
                .jsonPath("$.Resources[0].displayName").doesNotExist();

        client.post().uri("/scim/v2/Groups/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"filter\":\"displayName eq \\\"Dev Team\\\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidSyntax");
    }

    @Test
    @DisplayName("서버 루트 조회는 501 이다")
    void 서버_루트_조회는_501() {
        client.get().uri("/scim/v2?filter={f}", "userName eq \"park\"")
                .exchange().expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        client.post().uri("/scim/v2/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:SearchRequest\"]}")
                .exchange().expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    @DisplayName("ServiceProviderConfig 가 필터와 정렬 지원을 선언한다")
    void ServiceProviderConfig() {
        client.get().uri("/scim/v2/ServiceProviderConfig")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.filter.supported").isEqualTo(true)
                .jsonPath("$.filter.maxResults").isEqualTo(100)
                .jsonPath("$.sort.supported").isEqualTo(true);
    }
}

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Set;

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
    @DisplayName("조직 쓰기 응답도 members 를 빼면 조직 파티션을 다시 읽지 않는다")
    void 쓰기_응답도_members_를_빼면_읽지_않는다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"%s","displayName":"%s",
                 "members":[{"value":"park","type":"User"}]}
                """;

        // when — 같은 모양의 조직 둘을 만들되 하나만 members 를 뺀다
        state.findGroupCalls.clear();
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.formatted("grp-a", "A"))
                .exchange().expectStatus().isCreated();
        int 멤버포함 = state.findGroupCalls.size();

        state.findGroupCalls.clear();
        client.post().uri("/scim/v2/Groups?excludedAttributes=members").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.formatted("grp-b", "B"))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.members").doesNotExist();
        int 멤버제외 = state.findGroupCalls.size();

        // then — 응답을 그리려 읽던 한 번이 빠진다
        assertThat(멤버제외).isEqualTo(멤버포함 - 1);
    }

    @Test
    @DisplayName("단건 GET 과 쓰기 응답에도 attributes 를 적용한다")
    void 단건과_쓰기_응답의_속성_선택() {
        client.get().uri("/scim/v2/Users/park?attributes=userName")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("park")
                .jsonPath("$.userName").isEqualTo("park")
                .jsonPath("$.displayName").doesNotExist();

        client.post().uri("/scim/v2/Users?attributes=id")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"choi","active":true}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("choi")
                .jsonPath("$.userName").doesNotExist();
    }

    @Test
    @DisplayName("잘못된 속성 선택은 쓰기 전에 거절한다 — 상태가 바뀌지 않는다")
    void 잘못된_속성_선택은_쓰기_전에_거절한다() {
        client.post().uri("/scim/v2/Users?attributes=nickName")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"choi","active":true}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");

        assertThat(state.users).doesNotContainKey("choi");
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

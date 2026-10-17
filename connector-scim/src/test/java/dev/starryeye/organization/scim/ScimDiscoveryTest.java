package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /Schemas}·{@code /ResourceTypes} 가 RFC 7644 §4 대로 답하는지 본다(설계 2026-10-17 §5).
 */
class ScimDiscoveryTest {

    private static final String USER_NAME_ATTRIBUTE = "$.attributes[?(@.name=='userName')]";

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var state = new FakeStateRepository();
        var useCase = new IncrementalSyncUseCase(state, new FakeTupleWriter(), new FakeTupleChecker(), new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(query, useCase),
                        new ScimGroupHandler(state, query, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();
    }

    @Test
    @DisplayName("자원 종류 목록은 ListResponse 로 User 와 Group 을 준다")
    void 자원_종류_목록은_User_와_Group_이다() {
        // when, then
        client.get().uri("/scim/v2/ResourceTypes").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.LIST_RESPONSE)
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].id").isEqualTo("User")
                .jsonPath("$.Resources[0].endpoint").isEqualTo("/Users")
                .jsonPath("$.Resources[0].schema").isEqualTo(ScimSchemas.USER)
                .jsonPath("$.Resources[1].id").isEqualTo("Group")
                .jsonPath("$.Resources[0].meta.location").isEqualTo("/scim/v2/ResourceTypes/User");
    }

    @Test
    @DisplayName("자원 종류 단건은 이름의 대소문자를 가리지 않는다")
    void 자원_종류_단건은_대소문자를_가리지_않는다() {
        // when, then
        client.get().uri("/scim/v2/ResourceTypes/group").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo("Group")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.RESOURCE_TYPE);
    }

    @Test
    @DisplayName("모르는 자원 종류는 404 SCIM 오류다")
    void 모르는_자원_종류는_404다() {
        // when, then
        client.get().uri("/scim/v2/ResourceTypes/Device").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("스키마 목록은 ListResponse 로 코어 User 와 Group 을 준다")
    void 스키마_목록은_코어_User_와_Group_이다() {
        // when, then
        client.get().uri("/scim/v2/Schemas").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.LIST_RESPONSE)
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].id").isEqualTo(ScimSchemas.USER)
                .jsonPath("$.Resources[1].id").isEqualTo(ScimSchemas.GROUP);
    }

    @Test
    @DisplayName("스키마 URN 은 대소문자를 가리지 않는다")
    void URN_은_대소문자를_가리지_않는다() {
        // when, then
        client.get().uri("/scim/v2/Schemas/URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:USER").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(ScimSchemas.USER)
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.SCHEMA);
    }

    @Test
    @DisplayName("모르는 스키마는 404 SCIM 오류다 — enterprise 확장은 저장하지 않아 알리지 않는다")
    void 모르는_스키마는_404다() {
        // when, then
        client.get().uri("/scim/v2/Schemas/urn:ietf:params:scim:schemas:extension:enterprise:2.0:User").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("userName 은 필수이고 대소문자를 무시하며 서버에서 유일하다 — 표지판이 지키는 규칙 그대로다")
    void userName_은_필수이고_대소문자를_무시하며_서버에서_유일하다() {
        // when, then
        client.get().uri("/scim/v2/Schemas/" + ScimSchemas.USER).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath(USER_NAME_ATTRIBUTE + ".required").isEqualTo(true)
                .jsonPath(USER_NAME_ATTRIBUTE + ".caseExact").isEqualTo(false)
                .jsonPath(USER_NAME_ATTRIBUTE + ".uniqueness").isEqualTo("server");
    }

    @Test
    @DisplayName("조직 멤버의 type 은 User 와 Group 이다 — SCIM 이 중첩 그룹을 나타내는 유일한 수단이다")
    void 멤버의_type_은_User_와_Group_이다() {
        // when, then
        client.get().uri("/scim/v2/Schemas/" + ScimSchemas.GROUP).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.attributes[?(@.name=='members')].subAttributes[?(@.name=='type')].canonicalValues[*]")
                .isEqualTo(List.of("User", "Group"));
    }

    @Test
    @DisplayName("조직 멤버의 value 는 필수다 — value 없는 멤버는 POST·PUT·PATCH 모두 400 invalidSyntax 로 거절한다")
    void 멤버의_value_는_필수다() {
        // when, then
        client.get().uri("/scim/v2/Schemas/" + ScimSchemas.GROUP).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.attributes[?(@.name=='members')].subAttributes[?(@.name=='value')].required")
                .isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/scim/v2/Schemas", "/scim/v2/ResourceTypes"})
    @DisplayName("filter 가 오면 403 SCIM 오류다 — RFC 7644 §4")
    void filter_가_오면_403이다(String path) {
        // when, then
        client.get().uri(uri -> uri.path(path).queryParam("filter", "{f}").build("id eq \"x\"")).exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.status").isEqualTo("403");
    }

    @Test
    @DisplayName("filter 가 아닌 쿼리 파라미터는 무시한다 — 속성 선택·페이지를 받지 않아도 오류가 아니다")
    void 다른_쿼리_파라미터는_무시한다() {
        // when, then
        client.get().uri("/scim/v2/Schemas?attributes=id&count=1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                // count=1 을 받았다면 한 건만 담긴다 — 둘 다 담겨야 페이지 파라미터를 무시한 것이다
                .jsonPath("$.itemsPerPage").isEqualTo(2)
                .jsonPath("$.Resources").value(자원 -> assertThat((List<?>) 자원).hasSize(2))
                .jsonPath("$.Resources[0].attributes").exists();
    }

    @Test
    @DisplayName("속성과 하위 속성마다 description 이 있다 — RFC 7643 §7, userName 도 그렇다")
    void 속성마다_description_이_있다() {
        // given
        List<String> 설명_없는_속성 = new ArrayList<>();

        // when
        JsonNode 목록 = client.get().uri("/scim/v2/Schemas").exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        목록.get("Resources").forEach(schema -> 설명_없는_속성을_모은다(schema.get("attributes"), schema.get("name").asText(), 설명_없는_속성));

        // then
        assertThat(설명_없는_속성).isEmpty();
        JsonNode userName = 목록.get("Resources").get(0).get("attributes").get(0);
        assertThat(userName.get("name").asText()).isEqualTo("userName");
        assertThat(userName.get("description").asText()).isNotBlank();
    }

    @Test
    @DisplayName("스키마 접근자가 준 값을 바꿔도 다음 응답은 그대로다 — 공유 원본이 아니라 복사본을 준다")
    void 접근자는_복사본을_준다() {
        // given
        ((ObjectNode) ScimDiscovery.userSchema()).put("name", "바뀜");
        ((ObjectNode) ScimDiscovery.groupSchema()).put("name", "바뀜");

        // when, then
        assertThat(ScimDiscovery.userSchema().get("name").asText()).isEqualTo("User");
        assertThat(ScimDiscovery.groupSchema().get("name").asText()).isEqualTo("Group");
        client.get().uri("/scim/v2/Schemas/" + ScimSchemas.USER).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("User");
    }

    private static void 설명_없는_속성을_모은다(JsonNode attributes, String 경로, List<String> 모음) {
        attributes.forEach(attribute -> {
            String 이름 = 경로 + "." + attribute.get("name").asText();
            if (!attribute.hasNonNull("description") || attribute.get("description").asText().isBlank()) {
                모음.add(이름);
            }
            if (attribute.has("subAttributes")) {
                설명_없는_속성을_모은다(attribute.get("subAttributes"), 이름, 모음);
            }
        });
    }
}

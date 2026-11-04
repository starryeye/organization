package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.scim.fixture.ScimIdBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지정 API({@code PUT}·{@code DELETE /admin/organizations/{orgCode}/parent}, 설계 2026-11-04)를 실제 컨테이너 위에서 끝까지 본다 — 지정이 롤업이 되고,
 * IdP 의 멤버 교체·재적재·상위 삭제·없애기·wipe 를 지나는 동안 지정이 지켜지거나 정리되는지.
 *
 * <p>순서에 의존한다({@link Order}) — 앞 테스트가 만든 조직도 위에서 뒤 테스트가 이어 간다. 아이디는 서버가 정하므로({@link ScimIdBook}) 만들 때 받아 둔다.
 * 직원 {@code kim} 이 조직 {@code TEAM} 의 멤버이고, {@code DIV} 는 멤버가 빈 상위 후보다.
 *
 * <p>이력의 {@code traceId} 는 W3C {@code traceparent} 로 정해 보낸다 — {@code ScimAuditHistoryEndToEndTest} 와 같은 이유로 추적을 켠다.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureObservability(metrics = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimHierarchyEndToEndTest {

    /** {@code application-test.yml} 의 {@code dynamodb.table-name} 과 같아야 한다 */
    private static final String TABLE_NAME = "organization-scim-e2e";

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    /** 테스트 사이에 이어지는 조직도 이름 → 서버 id. 테스트 인스턴스는 메서드마다 새로 만들어지므로 정적이다. */
    private static final ScimIdBook 아이디들 = new ScimIdBook();

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired DirectoryStateRepository state;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** kim 이 상위 조직의 멤버로 롤업되는가 */
    private boolean kim이_속한다(String 조직) {
        return check("user:" + 서버("kim"), "member", "group:" + 서버(조직));
    }

    private void 만든다(String 이름, String uri, String body) {
        아이디들.기록한다(이름, 만든_아이디(client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()));
    }

    /** 멤버 없는 조직 하나 */
    private void 빈_조직을_만든다(String 이름, String displayName) {
        만든다(이름, "/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"%s","members":[]}
                """.formatted(displayName));
    }

    private String 서버(String 이름) {
        return 아이디들.서버(이름);
    }

    private WebTestClient.RequestHeadersSpec<?> 지정한다(String 대상, String 상위_코드) {
        return client.put().uri("/admin/organizations/{orgCode}/parent", 대상)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parentOrgCode\":\"" + 상위_코드 + "\"}");
    }

    private JsonNode 조직_상세(String 이름) {
        return client.get().uri("/admin/organizations/{orgCode}", 서버(이름)).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    @Test
    @Order(1)
    @DisplayName("평평한 조직에 상위를 지정하면 그 상위로 롤업이 생긴다")
    void 평평한_그룹에_상위를_지정하면_롤업이_생긴다() {
        // given — IdP 가 평평하게 보낸 조직 둘. 직원은 TEAM 에만 든다
        만든다("kim", "/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"kim","displayName":"김철수","active":true}
                """);
        만든다("TEAM", "/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"전략팀","members":[{"value":"%s","type":"User"}]}
                """.formatted(서버("kim")));
        빈_조직을_만든다("DIV", "전략본부");
        assertThat(kim이_속한다("TEAM")).isTrue();
        assertThat(kim이_속한다("DIV")).isFalse();

        // when
        지정한다(서버("TEAM"), 서버("DIV"))
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .exchange().expectStatus().isNoContent()
                .expectBody().isEmpty();

        // then
        assertThat(kim이_속한다("DIV")).isTrue();
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).contains(서버("DIV"));
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    @DisplayName("SCIM 에는 지정이 보이지 않고 관리 API 에는 보인다 — 지정은 IdP 의 멤버 목록이 아니다")
    void SCIM_에는_지정이_보이지_않고_관리_API_에는_보인다() {
        // when, then — IdP 가 읽는 조직 표현에는 TEAM 이 멤버로 없다
        client.get().uri("/scim/v2/Groups/{id}", 서버("DIV")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.members[?(@.value == '%s')]".formatted(서버("TEAM"))).doesNotExist();

        // when, then — 관리 API 는 지정한 상위와 하위를 보인다
        client.get().uri("/admin/organizations/{orgCode}", 서버("TEAM")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.assignedParentOrgCode").isEqualTo(서버("DIV"))
                .jsonPath("$.ancestors[0].orgCode").isEqualTo(서버("DIV"));
        client.get().uri("/admin/organizations/{orgCode}", 서버("DIV")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.childOrganizations[*].orgCode").value(코드들 ->
                        assertThat((List<String>) 코드들).contains(서버("TEAM")));
    }

    @Test
    @Order(3)
    @DisplayName("IdP 가 상위의 멤버를 통째로 바꿔도 지정한 롤업은 남는다")
    void IdP_가_멤버를_통째로_바꿔도_롤업이_남는다() {
        // when — 같은 이름에 멤버가 빈 PUT. 지정이 없었다면 TEAM 의 줄도 함께 사라질 자리다
        client.put().uri("/scim/v2/Groups/{id}", 서버("DIV")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"전략본부","members":[]}
                        """)
                .exchange().expectStatus().isOk();

        // then
        assertThat(kim이_속한다("DIV")).isTrue();
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).contains(서버("DIV"));
    }

    @Test
    @Order(4)
    @DisplayName("이력에 지정이 남는다 — 원인은 HIERARCHY_REQUEST, 행위자는 토큰의 클라이언트, 속성은 assignedParent")
    void 이력에_지정과_원인이_남는다() {
        // when
        JsonNode 맨_위 = client.get().uri("/admin/organizations/{orgCode}/history", 서버("TEAM")).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody()
                .get("items").get(0);

        // then
        assertThat(맨_위.get("cause").get("kind").asText()).isEqualTo("HIERARCHY_REQUEST");
        assertThat(맨_위.get("cause").get("actor").asText()).isEqualTo("client:test-client");
        assertThat(맨_위.get("cause").get("id").asText()).isEqualTo(TRACE_ID);
        assertThat(맨_위.get("attributes")).hasSize(1);
        assertThat(맨_위.get("attributes").get(0).get("name").asText()).isEqualTo("assignedParent");
        assertThat(맨_위.get("attributes").get(0).get("after").asText()).isEqualTo(서버("DIV"));
    }

    @Test
    @Order(5)
    @DisplayName("순환이면 409, 없는 상위나 없는 조직이면 404, 본문이 없으면 400 이고 아무것도 바뀌지 않는다")
    void 순환이면_409_없는_상위면_404_본문이_없으면_400() {
        // when, then — 상위 DIV 를 하위 TEAM 아래로 두면 순환이다
        지정한다(서버("DIV"), 서버("TEAM")).exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo(서버("TEAM") + " 를 " + 서버("DIV") + " 의 상위로 두면 순환이 된다");
        // when, then — 자기 자신도 순환이다
        지정한다(서버("TEAM"), 서버("TEAM")).exchange().expectStatus().isEqualTo(409);

        // when, then — 없는 상위
        지정한다(서버("TEAM"), "없는-상위").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("상위 조직이 없다: 없는-상위");
        // when, then — 없는 대상
        지정한다("없는-조직", 서버("DIV")).exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("조직이 없다: 없는-조직");

        // when, then — 본문이 없다
        client.put().uri("/admin/organizations/{orgCode}/parent", 서버("TEAM")).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("parentOrgCode 가 필요하다");

        // then — 거절한 요청들은 아무것도 바꾸지 않았다
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).contains(서버("DIV"));
        assertThat(state.findAssignedParent(서버("DIV")).blockOptional()).isEmpty();
        assertThat(kim이_속한다("DIV")).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("튜플 재적재가 직접 지운 롤업 줄을 지정에서 되살린다")
    void 재적재_뒤에도_롤업이_남는다() throws Exception {
        // given — 롤업 줄(TEAM child DIV)을 OpenFGA 에서 직접 지운다
        bootstrapper.client().deleteTuples(List.of(new ClientTupleKeyWithoutCondition()
                .user("group:" + 서버("TEAM")).relation("child")._object("group:" + 서버("DIV")))).get();
        assertThat(kim이_속한다("DIV")).isFalse();

        // when
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=tuples")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 정본의 지정이 줄로 돌아왔다
        assertThat(kim이_속한다("DIV")).isTrue();
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).contains(서버("DIV"));
    }

    @Test
    @Order(7)
    @DisplayName("IdP 가 상위 조직을 지우면 그 줄과 지정이 함께 사라진다")
    void 상위_조직을_IdP_가_지우면_줄과_지정이_사라진다() {
        // when
        client.delete().uri("/scim/v2/Groups/{id}", 서버("DIV")).exchange().expectStatus().isNoContent();

        // then
        assertThat(check("group:" + 서버("TEAM"), "child", "group:" + 서버("DIV"))).isFalse();
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).isEmpty();
        assertThat(조직_상세("TEAM").get("assignedParentOrgCode").isNull()).isTrue();
        assertThat(kim이_속한다("TEAM")).as("TEAM 자신의 멤버십은 그대로다").isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("지정을 없애면 롤업이 사라진다 — 없앨 지정이 없어도 204 다")
    void 없애면_롤업이_사라진다() {
        // given — 새 상위로 다시 지정한다
        빈_조직을_만든다("DIV2", "전략본부2");
        지정한다(서버("TEAM"), 서버("DIV2")).exchange().expectStatus().isNoContent();
        assertThat(kim이_속한다("DIV2")).isTrue();

        // when
        client.delete().uri("/admin/organizations/{orgCode}/parent", 서버("TEAM")).exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        // then
        assertThat(kim이_속한다("DIV2")).isFalse();
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).isEmpty();
        assertThat(kim이_속한다("TEAM")).isTrue();

        // when, then — 없앨 지정이 없는 조직은 바뀔 것이 없다. 없는 조직만 404 다
        client.delete().uri("/admin/organizations/{orgCode}/parent", 서버("TEAM")).exchange()
                .expectStatus().isNoContent();
        client.delete().uri("/admin/organizations/{orgCode}/parent", "없는-조직").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("조직이 없다: 없는-조직");
    }

    @Test
    @Order(9)
    @DisplayName("wipe 는 지정을 모두 지운다")
    void wipe_는_지정을_모두_지운다() {
        // given — 다시 지정한다
        빈_조직을_만든다("DIV3", "전략본부3");
        지정한다(서버("TEAM"), 서버("DIV3")).exchange().expectStatus().isNoContent();
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).contains(서버("DIV3"));

        // when
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=wipe&confirm=" + TABLE_NAME)
                .jsonPath("$.trigger").isEqualTo("RESET")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 조직도의 유일한 사본과 함께 지정도 사라졌다
        assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).isEmpty();
        assertThat(kim이_속한다("DIV3")).isFalse();
    }
}

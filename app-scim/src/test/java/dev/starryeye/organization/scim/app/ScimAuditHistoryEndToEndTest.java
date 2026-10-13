package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.core.fixture.Containers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변경 이력을 실제 컨테이너 위에서 본다(설계 2026-10-10 §9 끝단). SCIM 요청 뒤 관리 API 의 이력에 그 요청의 traceId 와 이전·이후 값이 나온다.
 * traceId 는 W3C {@code traceparent} 헤더로 정해 보낸다 — 서버가 그 trace 를 이어받는다.
 *
 * <p>{@code @SpringBootTest} 는 기본으로 추적을 꺼서({@code management.tracing.enabled=false}) 들어온 {@code traceparent} 를 읽는 전파기가 빠진다 —
 * 요청마다 새 trace 가 시작돼 헤더의 traceId 와 달라진다. 운영과 같게 추적을 켠다(지표는 다른 테스트처럼 그대로 둔다).
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureObservability(metrics = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimAuditHistoryEndToEndTest {

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

    @Autowired WebTestClient client;

    private JsonNode 보낸다(WebTestClient.RequestHeadersSpec<?> request, int status) {
        return request.exchange().expectStatus().isEqualTo(status)
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private String 직원을_만든다(String userName) {
        return 보낸다(client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s","displayName":"%s","active":true}
                        """.formatted(userName, userName)), 201).get("id").asText();
    }

    private String 조직을_만든다(String name, String memberId) {
        return 보낸다(client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"%s","members":[{"value":"%s"}]}
                        """.formatted(name, memberId)), 201).get("id").asText();
    }

    private JsonNode 이력(String path) {
        return 보낸다(client.get().uri(path), 200).get("items");
    }

    @Test
    @DisplayName("직원 PATCH 뒤 직원 이력의 맨 위 줄에 그 요청의 traceId 와 표시명의 이전·이후가 나온다")
    void PATCH_의_traceId_와_이전_이후() {
        // given
        String id = 직원을_만든다("audit-kim");
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        // when
        client.patch().uri("/scim/v2/Users/" + id)
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"displayName","value":"감사 김"}]}
                        """)
                .exchange().expectStatus().isOk();

        // then
        JsonNode items = 이력("/admin/employees/" + id + "/history");
        JsonNode 최근 = items.get(0);
        assertThat(최근.get("cause").get("source").asText()).isEqualTo("SCIM");
        assertThat(최근.get("cause").get("kind").asText()).isEqualTo("SCIM_REQUEST");
        assertThat(최근.get("cause").get("id").asText()).isEqualTo(traceId);
        assertThat(최근.get("attributes")).hasSize(1);
        assertThat(최근.get("attributes").get(0).get("name").asText()).isEqualTo("displayName");
        assertThat(최근.get("attributes").get(0).get("before").asText()).isEqualTo("audit-kim");
        assertThat(최근.get("attributes").get(0).get("after").asText()).isEqualTo("감사 김");
        assertThat(items.get(1).get("created").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("조직을 만들며 직원을 넣으면 조직 이력에는 멤버 들어옴이, 직원 이력에는 소속 들어옴과 얻은 권한이 나온다")
    void 조직_이력과_직원_이력() {
        // given
        String userId = 직원을_만든다("audit-lee");

        // when
        String groupId = 조직을_만든다("감사팀", userId);

        // then
        JsonNode 조직_최근 = 이력("/admin/organizations/" + groupId + "/history").get(0);
        assertThat(조직_최근.get("created").asBoolean()).isTrue();
        assertThat(조직_최근.get("membersAdded").get("count").asInt()).isEqualTo(1);
        assertThat(조직_최근.get("membersAdded").get("items").get(0).get("id").asText()).isEqualTo(userId);
        JsonNode 직원_최근 = 이력("/admin/employees/" + userId + "/history").get(0);
        assertThat(직원_최근.get("joined").get(0).asText()).isEqualTo(groupId);
        assertThat(직원_최근.get("granted").get(0).asText())
                .isEqualTo("user:" + userId + " direct_member group:" + groupId);
    }

    @Test
    @DisplayName("직원을 지워도 이력은 남는다 — 맨 위는 삭제, 그 아래는 생성")
    void 지워도_남는다() {
        // given
        String id = 직원을_만든다("audit-park");

        // when
        client.delete().uri("/scim/v2/Users/" + id).exchange().expectStatus().isNoContent();

        // then
        JsonNode items = 이력("/admin/employees/" + id + "/history");
        assertThat(items.get(0).get("deleted").asBoolean()).isTrue();
        assertThat(items.get(1).get("created").asBoolean()).isTrue();
    }
}

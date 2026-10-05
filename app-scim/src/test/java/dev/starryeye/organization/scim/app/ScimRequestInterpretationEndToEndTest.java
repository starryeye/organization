package dev.starryeye.organization.scim.app;

import com.jayway.jsonpath.JsonPath;
import dev.starryeye.organization.core.fixture.Containers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 요청 해석(설계 2026-10-06)이 실제 컨텍스트와 인프라 위에서 IdP 에게 닿는지 본다.
 *
 * <p>단위 테스트는 각 조각을 따로 본다 — 적용기의 갈래, 핸들러의 관찰자 호출, 라우터의 헤더. 여기서 확인하는 것은 다음이다.
 * <ul>
 *   <li>받아서 버린 속성의 이름이 관찰자를 거쳐 실제 앱의 {@code scim.patch.ignored} 메트릭에 잡힌다(관찰자 빈이 {@code ScimConfig} 에 실제로 이어졌다).</li>
 *   <li>조직 PATCH 가 externalId 를 바꾸고, 다른 조직이 쥔 값이면 실제 DynamoDB(GSI3)에서 락 안에 409 {@code uniqueness} 를 낸다.</li>
 *   <li>POST 201 의 {@code Location} 이 본문 {@code meta.location} 과 같다.</li>
 * </ul>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimRequestInterpretationEndToEndTest {

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

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
    @Autowired MeterRegistry registry;

    @Test
    @DisplayName("점검 M5 의 Entra 요청 — 전화번호 path 연산과 active=false 가 한 요청에 와도 비활성화되고, 버린 속성이 메트릭에 잡힌다")
    void 전화번호가_섞인_비활성화() {
        // given
        String id = 만든다("/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"m5-kim","active":true}""");
        double 전 = registry.counter("scim.patch.ignored", "attribute", "phoneNumbers").count();

        // when
        client.patch().uri("/scim/v2/Users/" + id).contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[
                   {"op":"replace","path":"phoneNumbers[type eq \\"work\\"].value","value":"010-1234-5678"},
                   {"op":"replace","value":{"active":false}}]}""")
                .exchange()
                .expectStatus().isOk();

        // then
        client.get().uri("/scim/v2/Users/" + id).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false);
        assertThat(registry.counter("scim.patch.ignored", "attribute", "phoneNumbers").count()).isEqualTo(전 + 1);
    }

    @Test
    @DisplayName("조직 PATCH 로 다른 조직의 externalId 를 가져오면 409 이고 그대로다, 새 값이면 바뀐다(④-1 이월)")
    void 조직_PATCH_externalId() {
        // given
        만든다("/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"가","externalId":"e2e-EXT-A"}""");
        String b = 만든다("/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"나","externalId":"e2e-EXT-B"}""");

        // when, then — 겹치면 409
        client.patch().uri("/scim/v2/Groups/" + b).contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","path":"externalId","value":"e2e-EXT-A"}]}""")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");
        client.get().uri("/scim/v2/Groups/" + b).exchange()
                .expectBody().jsonPath("$.externalId").isEqualTo("e2e-EXT-B");

        // when, then — 경로 없는 값으로 새 값
        client.patch().uri("/scim/v2/Groups/" + b).contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","value":{"externalId":"e2e-EXT-C"}}]}""")
                .exchange()
                .expectStatus().isNoContent();
        client.get().uri("/scim/v2/Groups/" + b).exchange()
                .expectBody().jsonPath("$.externalId").isEqualTo("e2e-EXT-C");
    }

    @Test
    @DisplayName("POST 201 의 Location 이 본문 meta.location 과 같다(점검 S1)")
    void POST_의_Location() {
        // when
        var result = client.post().uri("/scim/v2/Users").contentType(SCIM_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"s1-kim"}""")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();

        // then
        String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        assertThat(result.getResponseHeaders().getLocation())
                .hasToString((String) JsonPath.read(body, "$.meta.location"));
    }

    /** POST 하고 받은 id. */
    private String 만든다(String uri, String body) {
        return JsonPath.read(new String(client.post().uri(uri).contentType(SCIM_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8), "$.id");
    }
}

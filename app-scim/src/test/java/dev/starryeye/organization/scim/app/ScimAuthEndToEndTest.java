package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.scim.ScimRouter;
import dev.starryeye.organization.scim.ScimSchemas;
import dev.starryeye.organization.security.Scopes;
import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * app-scim 이 리소스 서버로 묶였는지 실제 컨테이너 위에서 본다(설계 2026-10-28 §4, A2·A5·A6·A7). 보안 필터체인은 두 앱의 시험이 모두 타지만,
 * 거절하는 쪽을 보는 것은 이 시험이다 — 다른 시험은 모든 범위를 가진 기본 머리({@code test-client})로 지나간다.
 *
 * <p>헬스 세부는 이 클래스만 운영 값({@code when-authorized})으로 띄워 본다. 두 앱의 시험 yml 은 {@code always} 라 세부가 늘 보인다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.show-details=when-authorized")
class ScimAuthEndToEndTest {

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

    /** 모든 범위를 가진 기본 머리({@code test-client})를 싣는 클라이언트. */
    @Autowired WebTestClient client;

    /** 기본 머리를 뺀 클라이언트 — 요청마다 머리를 지우는 것으로는 기본 머리가 빠지지 않는다. */
    private WebTestClient 토큰_없는;

    @BeforeEach
    void 토큰_없는_클라이언트를_만든다() {
        토큰_없는 = client.mutate().defaultHeaders(머리 -> 머리.remove(HttpHeaders.AUTHORIZATION)).build();
    }

    /** 범위 하나(또는 몇 개)만 가진 토큰을 기본 머리로 싣는 클라이언트. */
    private WebTestClient 범위만_가진(String... 범위) {
        return client.mutate().defaultHeaders(머리 -> 머리.setBearerAuth(TestTokens.발급한다("limited-client", 범위))).build();
    }

    private static String 직원_본문(String userName) {
        return """
                {"schemas":["%s"],"userName":"%s","displayName":"%s","active":true}
                """.formatted(ScimSchemas.USER, userName, userName);
    }

    @Test
    @DisplayName("토큰 없는 SCIM 쓰기는 401 SCIM Error 이고, 아무것도 바뀌지 않는다")
    void 토큰_없는_SCIM_쓰기는_401_SCIM_Error_이고_아무것도_바뀌지_않는다() {
        // given
        String userName = "anon-kim";

        // when
        토큰_없는.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON).bodyValue(직원_본문(userName))
                .exchange()

                // then
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.status").isEqualTo("401")
                .jsonPath("$.detail").isEqualTo("인증이 필요하다");
        client.get().uri(uri -> uri.path("/scim/v2/Users").queryParam("filter", "{f}").build("userName eq \"" + userName + "\""))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(0);
    }

    @Test
    @DisplayName("scim 범위 없는 토큰은 403 이다 — admin.read 만 가진 토큰")
    void scim_범위_없는_토큰은_403_이다() {
        // given
        WebTestClient 읽기만 = 범위만_가진(Scopes.ADMIN_READ);

        // when
        읽기만.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON).bodyValue(직원_본문("scope-kim"))
                .exchange()

                // then
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.status").isEqualTo("403")
                .jsonPath("$.detail").isEqualTo("이 요청에 필요한 범위가 없다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/scim/v2/ServiceProviderConfig", "/scim/v2/ResourceTypes", "/scim/v2/Schemas"})
    @DisplayName("발견도 토큰이 필요하다 — ServiceProviderConfig·ResourceTypes·Schemas 모두 토큰 없이 401 이다")
    void 발견도_토큰이_필요하다(String path) {
        // when, then
        토큰_없는.get().uri(path).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.status").isEqualTo("401");
    }

    @Test
    @DisplayName("authenticationSchemes 는 oauthbearertoken 하나다")
    void authenticationSchemes_는_oauthbearertoken_하나다() {
        // when, then
        client.get().uri("/scim/v2/ServiceProviderConfig").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticationSchemes.length()").isEqualTo(1)
                .jsonPath("$.authenticationSchemes[0].type").isEqualTo("oauthbearertoken")
                .jsonPath("$.authenticationSchemes[0].primary").isEqualTo(true);
    }

    @Test
    @DisplayName("SCIM 쓰기의 이력에 행위자가 남는다 — 토큰의 sub 가 client:<sub> 로")
    void SCIM_쓰기의_이력에_행위자가_남는다() {
        // given
        String id = client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON).bodyValue(직원_본문("actor-kim"))
                .exchange().expectStatus().isCreated()
                .expectBody(JsonNode.class).returnResult().getResponseBody().get("id").asText();

        // when, then
        client.get().uri("/admin/employees/{id}/history", id).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].cause.actor").isEqualTo("client:test-client");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    @DisplayName("프로브는 토큰 없이 200 이다 — 상태만 나오고 세부는 없다")
    void 프로브는_토큰_없이_200_이다(String path) {
        // when, then
        토큰_없는.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    @DisplayName("prometheus 는 토큰 없이 401 이다")
    void prometheus_는_토큰_없이_401_이다() {
        // when, then
        토큰_없는.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    @DisplayName("헬스 세부는 actuator 범위가 있을 때만 보인다 — 다른 범위의 토큰은 상태만")
    void 헬스_세부는_actuator_범위가_있을_때만() {
        // given
        WebTestClient 읽기만 = 범위만_가진(Scopes.ADMIN_READ);
        WebTestClient 운영 = 범위만_가진(Scopes.ACTUATOR);

        // when, then — 인증은 됐지만 actuator 범위가 없다
        읽기만.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
        // when, then — actuator 범위가 있다
        운영.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.openFga").exists();
    }
}

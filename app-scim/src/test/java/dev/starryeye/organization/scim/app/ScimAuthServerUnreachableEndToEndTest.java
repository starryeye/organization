package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.scim.ScimRouter;
import dev.starryeye.organization.scim.ScimSchemas;
import dev.starryeye.organization.security.Scopes;
import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * 인가 서버가 닿지 않아도 앱은 뜨고, 토큰이 있는 요청은 500 이 아니라 503 이다(설계 2026-10-28 A2·§4.1, 계획 Review Focus).
 * 키 목록은 첫 토큰을 볼 때 받으므로(기동 때 묻지 않는다) 인가 서버가 늦게 뜨거나 잠시 죽어도 앱 기동이 묶이지 않는다. 키 목록을 받지 못한 요청은 토큰 탓이
 * 아니라 검증하는 쪽의 장애라서 401 이 아니라 {@code 503 + Retry-After}(IdP 가 자격 문제로 읽어 격리하지 않게)이고 {@code WWW-Authenticate} 는 없다.
 *
 * <p>시험 디코더({@code test.auth.fixed-key})를 끄고 운영과 같은 키 목록 디코더를 쓴다 — 키 목록 주소는 닫힌 포트다. 시험 디코더가 꺼지면
 * 기본 머리도 없으므로 {@link TestTokens} 로 서명한 토큰을 요청마다 싣는다. 그 토큰의 서명은 맞지만 키 목록이 없어 검증하지 못한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.auth.fixed-key=false")
class ScimAuthServerUnreachableEndToEndTest {

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
        // 열었다 닫은 포트 — 연결이 곧바로 거절된다
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + 닫힌_포트() + "/oauth2/jwks");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
    }

    @Autowired WebTestClient client;

    private static int 닫힌_포트() {
        try (ServerSocket 소켓 = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return 소켓.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("닫힌 포트를 만들지 못했다", e);
        }
    }

    @Test
    @DisplayName("인가 서버가 닿지 않아도 뜨고, 프로브는 토큰 없이 200 이다")
    void 인가_서버가_닿지_않아도_뜬다() {
        // when, then — 이 메서드에 닿았다는 것이 기동했다는 뜻이다
        client.get().uri("/actuator/health/liveness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("인가 서버가 닿지 않아도 뜨고, 토큰 요청은 500 이 아니라 503 이다 — Retry-After 10, WWW-Authenticate 없음, SCIM Error")
    void 인가_서버가_닿지_않아도_뜨고_토큰_요청은_503_이다() {
        // given
        String 토큰 = TestTokens.발급한다("test-client", Scopes.SCIM);

        // when
        client.post().uri("/scim/v2/Users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["%s"],"userName":"unreachable-kim","active":true}
                        """.formatted(ScimSchemas.USER))
                .exchange()

                // then
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.status").isEqualTo("503")
                .jsonPath("$.detail").isEqualTo("일시 장애 — 잠시 뒤 다시 시도한다");
    }
}

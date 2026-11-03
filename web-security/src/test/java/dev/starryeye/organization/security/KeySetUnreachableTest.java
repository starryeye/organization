package dev.starryeye.organization.security;

import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인가 서버가 닿지 않을 때(설계 2026-10-28 A2·A5, 컨트롤러 판정 — 503). 시험 디코더를 끄고({@code test.auth.fixed-key=false}) 부트가
 * {@code jwk-set-uri} 로 만드는 운영과 같은 디코더를 쓰되, 그 주소는 닫힌 포트다. 기동은 인가 서버에 묻지 않으므로 뜨고, 토큰이 온 요청은 키 목록을 받지
 * 못해 검증하지 못한다 — 서버 오류(500)도, 좋은 토큰을 버리라는 401 {@code invalid_token} 도 아니라 일시 장애 503 + {@code Retry-After} 다.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = MockRoutesApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.auth.fixed-key=false")
@ExtendWith(OutputCaptureExtension.class)
class KeySetUnreachableTest {

    private static final String 일시_장애 = "일시 장애 — 잠시 뒤 다시 시도한다";

    @DynamicPropertySource
    static void 닫힌_키_목록(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + 닫힌_포트() + "/oauth2/jwks");
    }

    /** 잠깐 열었다 닫은 포트 — 그 번호로 가는 연결은 거절된다 */
    private static int 닫힌_포트() {
        try (var 소켓 = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return 소켓.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    WebTestClient client;

    @Test
    @DisplayName("키 목록을 받지 못하면 토큰이 온 요청은 503 + Retry-After 10 이고 WWW-Authenticate 를 달지 않는다 — 본문은 그 표면의 것."
            + " 기동과 프로브는 인가 서버와 무관하다. 원인은 WARN 한 줄로 남고 토큰은 남지 않는다")
    void 키_목록을_받지_못하면_503_이다(CapturedOutput 출력) {
        // given — 시험 키로 서명한 정상 토큰. 이 앱은 그 키를 키 목록에서 찾아야 한다
        String 토큰 = TestTokens.발급한다("test-client", TestTokens.ALL_SCOPES);

        // when, then
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk();
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectHeader().contentType(MediaType.parseMediaType("application/scim+json"))
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("503")
                .jsonPath("$.detail").isEqualTo(일시_장애);
        client.get().uri("/admin/employees").header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(4)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Service Unavailable")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo(일시_장애);
        client.get().uri("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody().isEmpty();
        assertThat(출력.getOut())
                .contains("WARN")
                .contains("키 목록을 받지 못해 503 으로 답한다")
                .contains("Connection refused")
                .doesNotContain(토큰);
    }

    @Test
    @DisplayName("JWE 꼴(다섯 조각) 토큰은 키 목록에 닿기 전에 거절되는 잘못된 토큰이다 — 키 목록이 없어도 503 이 아니라 401 invalid_token, WARN 없음")
    void JWE_꼴의_토큰은_401_이다(CapturedOutput 출력) {
        // when, then
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokenShapes.JWE_꼴()).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.status").isEqualTo("401");
        assertThat(출력.getOut()).doesNotContain("503 으로 답한다");
    }

    @Test
    @DisplayName("키 목록이 없어도 JWT 꼴이 아닌 토큰은 그대로 401 invalid_token 이다 — 키 없이도 잘못됐다고 아는 토큰")
    void 꼴이_잘못된_토큰은_그대로_401_이다() {
        // when, then
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.status").isEqualTo("401");
    }
}

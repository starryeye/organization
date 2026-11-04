package dev.starryeye.organization.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영과 같은 디코더 길(설계 2026-10-28 A2, 컨트롤러 판정 — 503 은 키 목록 탓일 때만). 시험 디코더를 끄고({@code test.auth.fixed-key=false}) 부트가
 * {@code jwk-set-uri} 로 만드는 디코더가 이 시험의 작은 HTTP 서버에서 키 목록({@link TestTokens#공개키()} 하나)을 받는다.
 *
 * <p>503 은 키 목록을 받지 못한 것(연결·상태·해석·시간 한도)에만 낸다. 키 목록이 멀쩡할 때 디코더가 토큰을 받아들이지 못한 것은 어떤 예외로 끝나든 401
 * {@code invalid_token} 이다 — 아무나 보낼 수 있는 토큰으로 "검증하는 쪽의 장애" 503 을 꾸며 내지 못한다.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = MockRoutesApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.auth.fixed-key=false")
@ExtendWith(OutputCaptureExtension.class)
class KeySetReachableTest {

    /** 키 목록 서버가 지금 내는 응답 — 시험마다 바꾸고 끝나면 되돌린다 */
    private enum 키_목록_응답 { 정상, 서버_오류, JSON_아님 }

    private static volatile 키_목록_응답 지금 = 키_목록_응답.정상;

    private static final HttpServer 키_목록_서버 = 띄운다();

    private static HttpServer 띄운다() {
        try {
            var 서버 = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            String 키_목록 = new JWKSet(new RSAKey.Builder(TestTokens.공개키()).build()).toString();
            서버.createContext("/oauth2/jwks", 교환 -> {
                int 상태 = 지금 == 키_목록_응답.서버_오류 ? 500 : 200;
                byte[] 본문 = (지금 == 키_목록_응답.JSON_아님 ? "<html>not json</html>" : 키_목록).getBytes(StandardCharsets.UTF_8);
                교환.getResponseHeaders().add("Content-Type", "application/json");
                교환.sendResponseHeaders(상태, 본문.length);
                교환.getResponseBody().write(본문);
                교환.close();
            });
            서버.start();
            return 서버;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void 키_목록_주소(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + 키_목록_서버.getAddress().getPort() + "/oauth2/jwks");
    }

    @AfterEach
    void 정상으로_되돌린다() {
        지금 = 키_목록_응답.정상;
    }

    @AfterAll
    static void 멈춘다() {
        키_목록_서버.stop(0);
    }

    @Autowired
    WebTestClient client;

    @Test
    @DisplayName("키 목록을 받으면 시험 키로 서명한 토큰이 통과하고 행위자가 실린다 — 키 목록 클라이언트(시간 한도·실패 표시)를 거친 운영 디코더 길")
    void 키_목록을_받으면_토큰이_통과한다() {
        // given
        String 토큰 = TestTokens.발급한다("test-client", Scopes.ADMIN_READ);

        // when, then
        client.get().uri("/admin/employees").header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("client:test-client");
    }

    @Test
    @DisplayName("키 목록이 멀쩡할 때 JWE 꼴(다섯 조각) 토큰은 401 invalid_token 이다 — 503·Retry-After·'장애' WARN 이 아니다")
    void JWE_꼴의_토큰은_401_이다(CapturedOutput 출력) {
        // when, then
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokenShapes.JWE_꼴()).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.status").isEqualTo("401")
                .jsonPath("$.detail").isEqualTo("인증이 필요하다");
        assertThat(출력.getOut()).doesNotContain("503 으로 답한다");
    }

    @Test
    @DisplayName("키 목록이 멀쩡할 때 다른 키로 서명한 토큰은 401 invalid_token 이다")
    void 다른_키로_서명한_토큰은_401_이다() {
        // when, then
        client.get().uri("/admin/employees")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.다른_키로_발급한다("test-client", Scopes.ADMIN_READ)).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("키 목록 서버가 5xx 로 답하면 키 목록을 받지 못한 것이다 — 503 + Retry-After 10")
    void 키_목록이_5xx_면_503_이다() {
        // given — 모르는 kid 라 캐시가 있어도 키 목록을 다시 받으러 간다
        지금 = 키_목록_응답.서버_오류;

        // when, then
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokenShapes.모르는_kid("5xx")).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    @DisplayName("키 목록 서버가 키 목록이 아닌 것으로 답하면 키 목록을 받지 못한 것이다 — 503 + Retry-After 10")
    void 키_목록이_해석되지_않으면_503_이다() {
        // given
        지금 = 키_목록_응답.JSON_아님;

        // when, then
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokenShapes.모르는_kid("garbage")).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10");
    }
}

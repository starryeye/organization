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
 * 인가 서버가 닿지 않을 때(설계 2026-10-28 A2·A5). 시험 디코더를 끄고({@code test.auth.fixed-key=false}) 부트가 {@code jwk-set-uri} 로 만드는 운영과 같은
 * 디코더를 쓰되, 그 주소는 닫힌 포트다. 기동은 인가 서버에 묻지 않으므로 뜨고, 토큰이 온 요청은 키 목록을 받지 못해 검증하지 못한다 — 서버 오류(500)가
 * 아니라 그 표면의 401 로 답한다.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = MockRoutesApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.auth.fixed-key=false")
@ExtendWith(OutputCaptureExtension.class)
class KeySetUnreachableTest {

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
    @DisplayName("키 목록을 받지 못하면 토큰이 온 SCIM 요청은 500 이 아니라 SCIM Error 401 이고 invalid_token 이다. 기동과 프로브는 인가 서버와 무관하다."
            + " 원인은 WARN 한 줄로 남고 토큰은 남지 않는다")
    void 키_목록을_받지_못하면_401_이다(CapturedOutput 출력) {
        // given — 시험 키로 서명한 정상 토큰. 이 앱은 그 키를 키 목록에서 찾아야 한다
        String 토큰 = TestTokens.발급한다("test-client", TestTokens.ALL_SCOPES);

        // when, then
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk();
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectHeader().contentType(MediaType.parseMediaType("application/scim+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo("401")
                .jsonPath("$.detail").isEqualTo("인증이 필요하다");
        assertThat(출력.getOut())
                .contains("WARN")
                .contains("토큰을 검증하지 못해 401 로 답한다")
                .contains("Could not obtain the keys")
                .doesNotContain(토큰);
    }
}

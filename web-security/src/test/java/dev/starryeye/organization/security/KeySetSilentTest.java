package dev.starryeye.organization.security;

import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 목록 주소가 연결만 받고 답하지 않을 때(블랙홀·멈춘 인가 서버). 리액터 네티의 기본값은 응답을 끝없이 기다리므로(연결 한도도 30초) 토큰이 온 요청마다 그만큼
 * 붙잡힌다. 키 목록을 받는 클라이언트에 시간 한도(연결 2초·응답 5초)를 두어 그 안팎에 503 으로 끝난다.
 *
 * <p>답하지 않는 서버는 {@link ServerSocket} 을 열고 {@code accept} 하지 않는 것이다 — 운영체제가 대기열에서 연결을 받아 두므로 연결은 되고 응답은 오지 않는다.
 * 연결 한도(2초)는 이 방법으로 재현되지 않는다(블랙홀 주소가 필요하다).
 */
@ActiveProfiles("test")
@SpringBootTest(classes = MockRoutesApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.auth.fixed-key=false")
class KeySetSilentTest {

    private static final ServerSocket 답하지_않는_서버 = 연다();

    @DynamicPropertySource
    static void 답하지_않는_키_목록(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + 답하지_않는_서버.getLocalPort() + "/oauth2/jwks");
    }

    private static ServerSocket 연다() {
        try {
            return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void 닫는다() throws IOException {
        답하지_않는_서버.close();
    }

    @Autowired
    WebTestClient client;

    @Test
    @DisplayName("키 목록이 답하지 않으면 요청은 응답 한도(5초) 안팎에 503 으로 끝난다 — 끝없이 붙잡히지 않는다")
    void 답하지_않는_키_목록은_요청을_붙잡지_않는다() {
        // given — 기다림이 한도 탓인지 보려고 시험 클라이언트는 넉넉히 기다린다
        String 토큰 = TestTokens.발급한다("test-client", TestTokens.ALL_SCOPES);
        WebTestClient 오래_기다리는 = client.mutate().responseTimeout(Duration.ofSeconds(20)).build();
        long 시작 = System.nanoTime();

        // when
        오래_기다리는.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10");
        Duration 걸림 = Duration.ofNanos(System.nanoTime() - 시작);

        // then
        assertThat(걸림).isLessThan(Duration.ofSeconds(10));
    }
}

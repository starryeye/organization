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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 목록 주소가 응답을 끝내지 않을 때(멈춘·느린 인가 서버, 블랙홀). 이 시험의 서버는 응답 머리를 곧바로 보내고 본문을 0.5초에 한 바이트씩 흘린다 — 읽기 사이의
 * 한도(리액터 네티 {@code responseTimeout})로는 끊기지 않는다. 리액터 네티 기본값은 응답을 끝없이 기다리므로, 키 목록을 받는 데 전체 한도(5초)를 두지 않으면
 * 토큰이 온 요청마다 그만큼 붙잡힌다. 한도를 넘기면 키 목록을 받지 못한 것이다 — 503.
 *
 * <p>아무것도 답하지 않는 서버는 이것의 한 경우다. 연결 한도(2초)는 블랙홀 주소가 있어야 재현되므로 시험하지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = MockRoutesApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.auth.fixed-key=false")
class KeySetTrickleTest {

    private static final ServerSocket 흘리는_서버 = 띄운다();

    private static ServerSocket 띄운다() {
        try {
            var 서버 = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            var 받는_일꾼 = new Thread(() -> {
                while (!서버.isClosed()) {
                    try {
                        Socket 연결 = 서버.accept();
                        var 흘리는_일꾼 = new Thread(() -> 흘린다(연결));
                        흘리는_일꾼.setDaemon(true);
                        흘리는_일꾼.start();
                    } catch (IOException e) {
                        return; // 닫혔다
                    }
                }
            });
            받는_일꾼.setDaemon(true);
            받는_일꾼.start();
            return 서버;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 요청 머리를 끝까지 읽고, 응답 머리를 보낸 뒤 본문을 0.5초에 한 바이트씩 흘린다. 연결이 끊기면 멈춘다 */
    private static void 흘린다(Socket 연결) {
        try (연결) {
            var 읽기 = new BufferedReader(new InputStreamReader(연결.getInputStream(), StandardCharsets.US_ASCII));
            String 줄;
            while ((줄 = 읽기.readLine()) != null && !줄.isEmpty()) {
                // 요청 머리를 버린다
            }
            OutputStream 쓰기 = 연결.getOutputStream();
            쓰기.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            쓰기.flush();
            for (int i = 0; i < 100_000; i++) {
                쓰기.write(' ');
                쓰기.flush();
                Thread.sleep(500);
            }
        } catch (IOException | InterruptedException e) {
            // 받는 쪽이 끊었다
        }
    }

    @DynamicPropertySource
    static void 흘리는_키_목록(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + 흘리는_서버.getLocalPort() + "/oauth2/jwks");
    }

    @AfterAll
    static void 닫는다() throws IOException {
        흘리는_서버.close();
    }

    @Autowired
    WebTestClient client;

    @Test
    @DisplayName("키 목록이 본문을 끝내지 않고 조금씩 흘리면 요청은 전체 한도(5초) 안팎에 503 으로 끝난다 — 읽기 사이 한도로는 끊기지 않는다")
    void 끝나지_않는_키_목록은_요청을_붙잡지_않는다() {
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

package dev.starryeye.organization.storage;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import dev.starryeye.organization.core.fixture.Containers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 컨테이너가 받은 호스트 포트를 다른 프로그램이 {@code 127.0.0.1} 로 이미 쓰고 있어도 속지 않는다.
 *
 * <p>Docker Desktop(macOS)은 호스트 포트를 VM 안에서 골라 macOS 의 {@code 127.0.0.1} 전용 리스너(IntelliJ 등)와 같은 번호를 줄 수
 * 있다. 그때 {@code localhost} 로 가는 요청은 그 프로그램이 받는다 — 테스트가 DynamoDB 대신 엉뚱한 응답(HTTP 999 등)을 받던
 * 간헐 실패의 원인이다. 여기서는 가짜를 {@code 127.0.0.1} 에 먼저 띄우고 컨테이너를 그 포트에 붙여, 대기 조건이 가짜를 알아채고
 * 시작을 거부하는지 본다(실제로는 {@link Containers#시작_시도} 번까지 새 포트로 다시 띄운다).
 */
class ContainersSquatterTest {

    /** {@code 127.0.0.1} 에만 묶어 요청마다 같은 응답을 주는 가짜 프로그램. */
    private static Thread 가짜로_답한다(ServerSocket 가짜, String 응답) {
        Thread 스레드 = new Thread(() -> {
            try {
                while (!가짜.isClosed()) {
                    try (Socket 연결 = 가짜.accept()) {
                        요청을_읽는다(연결.getInputStream());
                        OutputStream out = 연결.getOutputStream();
                        out.write(응답.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // 테스트가 끝나 가짜가 닫혔다
            }
        });
        스레드.setDaemon(true);
        스레드.start();
        return 스레드;
    }

    private static void 요청을_읽는다(InputStream in) throws IOException {
        int 이어진_줄끝 = 0;
        for (int b = in.read(); b != -1; b = in.read()) {
            이어진_줄끝 = (b == '\r' || b == '\n') ? 이어진_줄끝 + 1 : 0;
            if (이어진_줄끝 == 4) {
                return;
            }
        }
    }

    /** 컨테이너 포트를 가짜가 쓰는 호스트 포트에 붙인다. 가짜에 걸렸을 때 빨리 끝나도록 한 번만, 짧게 기다린다. */
    private static GenericContainer<?> 그_포트에_붙인다(GenericContainer<?> 컨테이너, int 컨테이너_포트, int 호스트_포트) {
        return 컨테이너
                .withStartupAttempts(1)
                .withStartupTimeout(Duration.ofSeconds(10))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(호스트_포트), ExposedPort.tcp(컨테이너_포트))));
    }

    @Test
    @DisplayName("DynamoDB 포트를 다른 프로그램이 127.0.0.1 로 쓰고 있으면 400 을 주더라도 속지 않고 시작을 거부한다")
    void DynamoDB_는_가짜에_속지_않는다() throws IOException {
        try (ServerSocket 가짜 = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            // given — DynamoDB 처럼 400 을 주지만 DynamoDB 는 아닌 프로그램
            가짜로_답한다(가짜, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            var 컨테이너 = 그_포트에_붙인다(Containers.dynamoDb(), 8000, 가짜.getLocalPort());

            // when, then
            try {
                assertThatThrownBy(컨테이너::start).isInstanceOf(ContainerLaunchException.class);
            } finally {
                컨테이너.stop();
            }
        }
    }

    @Test
    @DisplayName("OpenFGA 포트를 다른 프로그램이 127.0.0.1 로 쓰고 있으면 /healthz 가 200 이어도 속지 않고 시작을 거부한다")
    void OpenFGA_는_가짜에_속지_않는다() throws IOException {
        try (ServerSocket 가짜 = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            // given — 어떤 경로에나 200 OK 를 주는 프로그램
            가짜로_답한다(가짜, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK");
            var 컨테이너 = 그_포트에_붙인다(Containers.openFga(), 8080, 가짜.getLocalPort());

            // when, then
            try {
                assertThatThrownBy(컨테이너::start).isInstanceOf(ContainerLaunchException.class);
            } finally {
                컨테이너.stop();
            }
        }
    }
}

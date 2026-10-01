package dev.starryeye.organization.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LDAP 연결·읽기 타임아웃(점검 C5) — JNDI 환경 값 변환과, bind 응답이 없을 때 connect-timeout 이 끊는 경로.
 * 타임아웃이 없으면 JNDI 는 응답이 올 때까지 기다린다 — 죽은 연결 하나에 회차가 끝나지 않아 작업 락이 안 풀리고
 * 이후 매일 동기화가 건너뛰어진다.
 *
 * <p>페이징 도중(bind 이후) 응답이 끊겨 read-timeout 이 끊는 경로는 {@link LdapReadTimeoutDuringPagingTest} 가 고정한다.
 */
class LdapTimeoutTest {

    @Test
    @DisplayName("기본 타임아웃은 연결 10초·읽기 150초다")
    void 기본값() {
        // when
        var timeouts = LdapConfig.jndiTimeouts(new LdapProperties());

        // then
        assertThat(timeouts)
                .containsEntry("com.sun.jndi.ldap.connect.timeout", "10000")
                .containsEntry("com.sun.jndi.ldap.read.timeout", "150000");
    }

    @Test
    @DisplayName("설정한 타임아웃이 JNDI 환경 값(밀리초)으로 실린다")
    void 설정값이_실린다() {
        // given
        var properties = new LdapProperties();
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(7));

        // when
        var timeouts = LdapConfig.jndiTimeouts(properties);

        // then
        assertThat(timeouts)
                .containsEntry("com.sun.jndi.ldap.connect.timeout", "3000")
                .containsEntry("com.sun.jndi.ldap.read.timeout", "7000");
    }

    @Test
    @DisplayName("인증(bind) 응답이 없으면 connect-timeout 으로 끝나고 다시 읽는다")
    void 인증_응답이_없으면_연결_타임아웃으로_끝난다() throws IOException {
        try (ServerSocket 침묵하는_서버 = new ServerSocket(0)) {
            // given — 접속은 받아 두기만 하고 아무것도 쓰지 않는다
            List<Socket> 받은_접속 = new CopyOnWriteArrayList<>();
            Thread 받기 = new Thread(() -> {
                try {
                    while (true) {
                        받은_접속.add(침묵하는_서버.accept());
                    }
                } catch (IOException ignored) {
                    // 테스트가 끝나 서버 소켓이 닫혔다
                }
            });
            받기.setDaemon(true);
            받기.start();

            LdapProperties properties = new LdapProperties();
            properties.setUrl("ldap://localhost:" + 침묵하는_서버.getLocalPort());
            properties.setBindDn("cn=admin,dc=example,dc=com");
            properties.setBindPassword("password");
            // bindDn 을 쓰면 컨텍스트 생성이 곧 동기 bind 다 — JNDI LDAP provider 는 그 응답
            // 대기를 read-timeout 이 아니라 connect-timeout 으로 잰다(JDK-8194264). 두 값을
            // 다르게 둬서 실제로 끊는 쪽이 connect-timeout 임을 메시지의 숫자로 못박는다.
            properties.setConnectTimeout(Duration.ofSeconds(1));
            properties.setReadTimeout(Duration.ofSeconds(60));
            properties.setMaxRetries(1);

            LdapConfig config = new LdapConfig();
            LdapContextSource contextSource = config.ldapContextSource(properties);
            contextSource.afterPropertiesSet();
            var source = config.ldapDirectorySnapshotSource(
                    config.ldapTemplate(contextSource),
                    config.ldapMappingStrategy(properties, Clock.systemUTC()),
                    properties);

            try {
                // when, then — connect-timeout(1초)이 끊는다. read-timeout(60초)이 대신
                // 걸렸다면 20초 안에 이 예외가 나오지 않는다.
                assertThatThrownBy(() -> source.fetchAll().block(Duration.ofSeconds(20)))
                        .hasStackTraceContaining("timeout used: 1000 ms");
                assertThat(받은_접속).as("최초 1회 + 재시도 1회 — 시도마다 연결 하나").hasSize(2);
            } finally {
                for (Socket socket : 받은_접속) {
                    socket.close();
                }
            }
        }
    }
}

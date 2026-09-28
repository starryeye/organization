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
 * LDAP 연결·읽기 타임아웃(점검 C5). 타임아웃이 없으면 JNDI 는 응답이 올 때까지 기다린다 — 죽은 연결 하나에 회차가 끝나지 않아
 * 실행 가드가 안 풀리고 이후 매일 동기화가 건너뛰어진다.
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
    @DisplayName("접속만 받고 응답하지 않는 서버에서는 읽기 타임아웃으로 실패하고, 일시 장애로 보고 다시 읽는다")
    void 응답_없는_서버는_타임아웃으로_끝난다() throws IOException {
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
            // connect-timeout 도 줄인다 — JNDI LDAP provider 는 인증(bind) 의 응답 대기를
            // read.timeout 이 아니라 connect.timeout 으로 잰다(JDK-8194264, 이 JDK 17 로 직접
            // 확인). bindDn 을 쓰는 이 시나리오는 컨텍스트 생성이 곧 bind 라, connect-timeout 을
            // 기본값(10초)으로 두면 시도마다 10초씩 걸려 재시도까지 20초를 넘겨 버려
            // block(20초) 이 먼저 포기해 버린다(그 예외에는 "read timed out" 이 없다).
            properties.setConnectTimeout(Duration.ofSeconds(1));
            properties.setReadTimeout(Duration.ofSeconds(1));
            properties.setMaxRetries(1);

            LdapConfig config = new LdapConfig();
            LdapContextSource contextSource = config.ldapContextSource(properties);
            contextSource.afterPropertiesSet();
            var source = config.ldapDirectorySnapshotSource(
                    config.ldapTemplate(contextSource),
                    config.ldapMappingStrategy(properties, Clock.systemUTC()),
                    properties);

            // when, then — 타임아웃이 없으면 20초 뒤 block 이 먼저 포기한다(그 예외에는 read timed out 이 없다)
            assertThatThrownBy(() -> source.fetchAll().block(Duration.ofSeconds(20)))
                    .hasStackTraceContaining("read timed out");
            assertThat(받은_접속).as("최초 1회 + 재시도 1회 — 시도마다 연결 하나").hasSize(2);

            for (Socket socket : 받은_접속) {
                socket.close();
            }
        }
    }
}

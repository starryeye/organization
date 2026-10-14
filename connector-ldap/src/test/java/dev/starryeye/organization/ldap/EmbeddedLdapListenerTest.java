package dev.starryeye.organization.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 임베디드 LDAP 리스너가 남의 포트를 몰래 함께 쓰지 않는다. macOS 는 모든 주소(`*`) 리스너가 127.0.0.1 전용 리스너(IntelliJ 등)와 같은 포트 번호를
 * 함께 쓰게 두고, 127.0.0.1 연결은 전용 쪽이 받는다 — 바인드 요청이 엉뚱한 프로세스로 가 "LDAP connection has been closed" 로 끊기던 간헐 실패다.
 */
class EmbeddedLdapListenerTest {

    private static final String BASE_DN = "dc=example,dc=com";

    private InMemoryDirectoryServer server;

    @AfterEach
    void 내린다() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    private static InMemoryDirectoryServer 서버(InMemoryListenerConfig 리스너) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(리스너);
        config.setSchema(null);
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.add("dn: " + BASE_DN, "objectClass: domain", "dc: example");
        return server;
    }

    @Test
    @DisplayName("남이 127.0.0.1 로 쥔 포트에는 뜨지 않는다 — 모든 주소로 떠서 그 포트를 함께 쓰면 127.0.0.1 연결이 남에게 간다")
    void 남이_127_0_0_1_로_쥔_포트에는_뜨지_않는다() throws Exception {
        // given — IntelliJ 처럼 127.0.0.1 에만 뜬 리스너가 포트를 쥐고 있다
        try (ServerSocket 점유자 = new ServerSocket()) {
            점유자.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server = 서버(EmbeddedLdapListener.리스너("test", 점유자.getLocalPort()));

            // when
            Throwable 실패 = catchThrowable(server::startListening);

            // then
            assertThat(실패).as("같은 포트에 뜨지 못해야 포트 0 일 때 운영체제가 다른 포트를 고른다")
                    .isInstanceOf(LDAPException.class)
                    .hasMessageContaining("BindException");
        }
    }

    @Test
    @DisplayName("접속 주소는 리스너가 뜬 127.0.0.1 이다 — localhost 가 다른 주소로 풀려도 엉뚱한 곳에 붙지 않는다")
    void 접속_주소는_리스너가_뜬_127_0_0_1_이다() throws Exception {
        // given
        server = 서버(EmbeddedLdapListener.리스너("test"));
        server.startListening();

        // when
        String 주소 = EmbeddedLdapListener.접속_주소(server);
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(주소);
        contextSource.setBase(BASE_DN);
        contextSource.setUserDn("cn=admin," + BASE_DN);
        contextSource.setPassword("adminpassword");
        contextSource.setBaseEnvironmentProperties(LdapConfig.타임아웃_환경(new LdapProperties()));
        contextSource.afterPropertiesSet();
        LdapTemplate template = LdapTemplates.configured(contextSource);

        // then
        assertThat(주소).isEqualTo("ldap://127.0.0.1:" + server.getListenPort());
        assertThat(template.lookup("").toString()).as("바인드하고 베이스를 읽는다").isNotEmpty();
    }
}

package dev.starryeye.organization.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * UnboundID in-memory LDAP 서버. 도커 없이 밀리초 단위로 뜬다.
 * 로컬에서 실제 OpenLDAP 으로 확인하는 것은 docker-compose 쪽 몫이다.
 */
public abstract class EmbeddedLdapSupport {

    protected static final String BASE_DN = "dc=example,dc=com";
    protected static final String BIND_DN = "cn=admin," + BASE_DN;
    protected static final String BIND_PASSWORD = "adminpassword";

    private InMemoryDirectoryServer server;
    protected LdapTemplate ldapTemplate;

    /** 각 테스트가 자기 조직도 LDIF 를 준다 */
    protected abstract String ldif();

    @BeforeEach
    void LDAP서버를_띄운다() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
        config.setSchema(null);

        server = new InMemoryDirectoryServer(config);
        server.importFromLDIF(true,
                new com.unboundid.ldif.LDIFReader(
                        new ByteArrayInputStream(ldif().getBytes(StandardCharsets.UTF_8))));
        server.startListening();

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl("ldap://localhost:" + server.getListenPort());
        contextSource.setBase(BASE_DN);
        contextSource.setUserDn(BIND_DN);
        contextSource.setPassword(BIND_PASSWORD);
        contextSource.afterPropertiesSet();

        ldapTemplate = new LdapTemplate(contextSource);
        ldapTemplate.setIgnorePartialResultException(true);
    }

    @AfterEach
    void LDAP서버를_내린다() {
        if (server != null) {
            server.shutDown(true);
        }
    }
}

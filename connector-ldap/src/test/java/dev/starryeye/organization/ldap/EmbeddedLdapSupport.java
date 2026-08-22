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

    /**
     * 서버측 엔트리 상한. 0 이면 무제한이다.
     *
     * <p>실제 디렉터리(Active Directory 의 {@code MaxPageSize} 등)는 상한을 두고, 그 위로는
     * 결과를 자른 뒤 {@code SIZE_LIMIT_EXCEEDED} 를 붙여 응답한다. 상한이 없는 서버에서는
     * 페이징 없는 검색도 전체를 반환하므로, "조용한 잘림" 이라는 원래 결함을 재현할 수 없다.
     */
    protected int maxSizeLimit() {
        return 0;
    }

    @BeforeEach
    void LDAP서버를_띄운다() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
        config.setSchema(null);
        if (maxSizeLimit() > 0) {
            config.setMaxSizeLimit(maxSizeLimit());
        }

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
        // 프로덕션 LdapConfig 와 같은 설정. 이것이 false 여야 서버가 결과를 자른 사실이
        // 예외로 올라온다 — true 로 두면 잘린 목록이 대량 퇴사처럼 보여 실제 소속을 지운다.
        ldapTemplate.setIgnoreSizeLimitExceededException(false);
    }

    @AfterEach
    void LDAP서버를_내린다() {
        if (server != null) {
            server.shutDown(true);
        }
    }
}

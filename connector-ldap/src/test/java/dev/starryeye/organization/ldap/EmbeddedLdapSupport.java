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

    /** 개명(ModifyDN)·속성 추가처럼 서버를 바꾸는 시나리오가 쓴다. */
    protected InMemoryDirectoryServer server;
    protected LdapTemplate ldapTemplate;

    /**
     * 이름 기반 식별 속성(직원 {@code uid}, groupOfNames 조직 {@code cn}, DIT 조직 {@code ou}, 로그인 {@code uid})을 못박은 설정.
     * 기본값은 불변 id({@code entryUUID})로 바뀌었다(설계 2026-10-04 §4.1). 식별자가 주제가 아니고 이름 기반 기대값에 기대는 테스트가
     * 기본값 대신 이것을 쓴다. 접속 정보는 담지 않는다.
     */
    public static LdapProperties 이름기반() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserIdAttribute("uid");
        g.setUserLoginAttribute("uid");
        g.setGroupIdAttribute("cn");
        var d = properties.getDit();
        d.setUserIdAttribute("uid");
        d.setUserLoginAttribute("uid");
        d.setGroupIdAttribute("ou");
        return properties;
    }

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

    /**
     * 서버를 만들기 전에 설정을 더 손볼 자리. 서버가 받는 요청을 엿보는 인터셉터를 다는 테스트가 쓴다 —
     * 접속 설정을 따로 적지 않고 이 클래스의 템플릿을 그대로 쓰기 위해서다.
     */
    protected void 서버설정을_고친다(InMemoryDirectoryServerConfig config) {
    }

    /** 템플릿의 접속·읽기 한도를 정하는 설정. 기본은 운영 기본값({@link LdapProperties})이다 — 한도를 시험하는 테스트만 줄인다. */
    protected LdapProperties 접속_한도() {
        return new LdapProperties();
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
        서버설정을_고친다(config);

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
        // 접속·읽기 한도는 운영과 같은 도우미로 건다 — 없으면 응답이 오지 않는 읽기 하나가 테스트를 끝없이 붙잡는다.
        // 이진 속성 선언은 일부러 싣지 않는다(아래 LdapConfig로_만든_템플릿 참고)
        contextSource.setBaseEnvironmentProperties(LdapConfig.타임아웃_환경(접속_한도()));
        contextSource.afterPropertiesSet();

        // 운영(LdapConfig)과 같은 자리에서 만든다. 설정을 여기 따로 적으면 운영과 검증이
        // 표류한다 — 특히 ignoreSizeLimitExceededException 이 어긋나면 잘린 목록이 대량
        // 퇴사처럼 보이는 결함을 검증이 못 본다.
        ldapTemplate = LdapTemplates.configured(contextSource);
    }

    /**
     * 운영과 같은 컨텍스트 소스({@link LdapConfig#ldapContextSource})로 만든 템플릿. {@link #ldapTemplate} 에는 JNDI 이진 선언이 없지만
     * 이것에는 있다 — 식별 속성이 {@code objectGUID} 일 때와 AD 그룹의 {@code objectSid} 를 이진으로 받으려면 이쪽을 쓴다.
     * 접속 정보를 {@code properties} 에 채운다.
     */
    protected LdapTemplate LdapConfig로_만든_템플릿(LdapProperties properties) throws Exception {
        properties.setUrl("ldap://localhost:" + server.getListenPort());
        properties.setBindDn(BIND_DN);
        properties.setBindPassword(BIND_PASSWORD);
        LdapContextSource contextSource = new LdapConfig().ldapContextSource(properties);
        contextSource.afterPropertiesSet();
        return LdapTemplates.configured(contextSource);
    }

    @AfterEach
    void LDAP서버를_내린다() {
        if (server != null) {
            server.shutDown(true);
        }
    }
}

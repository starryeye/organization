package dev.starryeye.organization.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import dev.starryeye.organization.core.port.DirectoryReadException;
import dev.starryeye.organization.core.port.DirectoryUnavailableException;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 한 사람 읽기 어댑터(설계 2026-11-28 V2·§5.2) — 주소를 차례로 한 번씩, 일시 장애 신호만 다음 주소·503 쪽이다. 시간은 단언하지 않고 결과 종류만 본다.
 */
class LdapDirectoryAccountSourceTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final String BIND_DN = "cn=admin," + BASE_DN;
    private static final String BIND_PASSWORD = "adminpassword";
    private static final String KIM_DN = "uid=kim,ou=people," + BASE_DN;

    private static final String LDIF = """
            dn: dc=example,dc=com
            objectClass: top
            objectClass: domain
            dc: example

            dn: ou=people,dc=example,dc=com
            objectClass: organizationalUnit
            ou: people

            dn: uid=kim,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: kim
            cn: Kim Chulsoo
            sn: Kim
            """;

    private final List<InMemoryDirectoryServer> 서버들 = new ArrayList<>();

    @AfterEach
    void 서버를_내린다() {
        서버들.forEach(서버 -> 서버.shutDown(true));
    }

    @Test
    @DisplayName("첫 주소의 검색이 바쁘면 둘째 주소에서 답한다")
    // 깨뜨릴 변경: ServiceUnavailableException 을 일시 장애로 보지 않거나 다음 템플릿으로 넘기지 않으면 이 시험이 깨진다
    void 첫_주소의_검색이_바쁘면_둘째_주소에서_답한다() throws Exception {
        // given
        var A = 서버(BIND_PASSWORD, new 검색을_바쁘게_거절한다());
        var B = 서버(BIND_PASSWORD);
        var source = 원천(List.of(주소(A), 주소(B)), Duration.ofSeconds(30), Duration.ofSeconds(10));

        // when
        var 상태 = source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30));

        // then
        assertThat(상태).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    @DisplayName("첫 주소가 인증을 거절하면 넘기지 않고 DirectoryReadException 이다")
    // 깨뜨릴 변경: 인증 실패까지 다음 주소로 넘기면 B 의 바인드 수가 0 이 아니게 된다
    void 첫_주소가_인증을_거절하면_넘기지_않고_DirectoryReadException() throws Exception {
        // given
        var A = 서버("다른비밀번호");
        var B바인드 = new 바인드를_센다();
        var B = 서버(BIND_PASSWORD, B바인드);
        var source = 원천(List.of(주소(A), 주소(B)), Duration.ofSeconds(30), Duration.ofSeconds(10));

        // when, then
        assertThatThrownBy(() -> source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30)))
                .isInstanceOf(DirectoryReadException.class)
                .hasMessageNotContaining("kim")
                .hasCauseInstanceOf(org.springframework.ldap.AuthenticationException.class);
        assertThat(B바인드.횟수()).as("인증 실패는 다음 주소로 넘기지 않는다").isZero();
    }

    @Test
    @DisplayName("모든 주소가 바쁘면 DirectoryUnavailableException 이다")
    // 깨뜨릴 변경: 다 쓴 뒤 일시 장애를 DirectoryReadException 으로 바꾸면 이 시험이 깨진다
    void 모든_주소가_바쁘면_DirectoryUnavailableException() throws Exception {
        // given
        var A = 서버(BIND_PASSWORD, new 검색을_바쁘게_거절한다());
        var B = 서버(BIND_PASSWORD, new 검색을_바쁘게_거절한다());
        var source = 원천(List.of(주소(A), 주소(B)), Duration.ofSeconds(30), Duration.ofSeconds(10));

        // when, then
        assertThatThrownBy(() -> source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30)))
                .isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    @DisplayName("주소 상한을 넘으면 다음 주소로 넘긴다")
    // 깨뜨릴 변경: 시도마다 건 timeout 을 빼면 첫 주소의 2초 잠을 기다리다 전체 상한에서야 끊긴다
    void 주소_상한을_넘으면_다음_주소로_넘긴다() throws Exception {
        // given
        var A = 서버(BIND_PASSWORD, new 검색에서_잔다(Duration.ofSeconds(2)));
        var B = 서버(BIND_PASSWORD);
        var source = 원천(List.of(주소(A), 주소(B)), Duration.ofSeconds(30), Duration.ofMillis(200));

        // when
        var 상태 = source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30));

        // then
        assertThat(상태).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    @DisplayName("전체 상한을 넘으면 DirectoryUnavailableException 이다")
    // 깨뜨릴 변경: 바깥 timeout 을 빼거나 그 TimeoutException 을 그대로 두면 이 시험이 깨진다
    void 전체_상한을_넘으면_DirectoryUnavailableException() throws Exception {
        // given
        var A = 서버(BIND_PASSWORD, new 검색에서_잔다(Duration.ofSeconds(2)));
        var B = 서버(BIND_PASSWORD, new 검색에서_잔다(Duration.ofSeconds(2)));
        var source = 원천(List.of(주소(A), 주소(B)), Duration.ofMillis(300), Duration.ofMillis(200));

        // when, then
        assertThatThrownBy(() -> source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30)))
                .isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    @DisplayName("검색 베이스가 없으면 DirectoryReadException 이다")
    // 깨뜨릴 변경: NameNotFoundException 을 일시 장애로 보거나 ABSENT 로 바꾸면 설정 오류가 503·없음으로 묻힌다
    void 검색_베이스가_없으면_DirectoryReadException() throws Exception {
        // given
        var A = 서버(BIND_PASSWORD);
        var properties = 설정(List.of(주소(A)));
        properties.getGroupOfNames().setUserSearchBase("ou=nowhere");
        var source = 원천(properties, Duration.ofSeconds(30), Duration.ofSeconds(10));

        // when, then
        assertThatThrownBy(() -> source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30)))
                .isInstanceOf(DirectoryReadException.class)
                .hasCauseInstanceOf(org.springframework.ldap.NameNotFoundException.class);
    }

    private static LdapProperties 설정(List<String> 주소들) {
        var properties = EmbeddedLdapSupport.이름기반();
        properties.setUrls(주소들);
        properties.setBindDn(BIND_DN);
        properties.setBindPassword(BIND_PASSWORD);
        properties.getGroupOfNames().setUserSearchBase("ou=people");
        properties.getGroupOfNames().setUserFilter("(objectClass=inetOrgPerson)");
        return properties;
    }

    private static LdapDirectoryAccountSource 원천(List<String> 주소들, Duration 전체, Duration 주소당) throws Exception {
        return 원천(설정(주소들), 전체, 주소당);
    }

    /** 운영과 같이 {@link LdapConfig} 가 시작 주소별 템플릿을 만든다. */
    private static LdapDirectoryAccountSource 원천(LdapProperties properties, Duration 전체, Duration 주소당) throws Exception {
        LdapConfig config = new LdapConfig();
        LdapContextSource contextSource = config.ldapContextSource(properties);
        contextSource.afterPropertiesSet();
        LdapTemplate 첫_템플릿 = config.ldapTemplate(contextSource);
        LdapMappingStrategy strategy = config.ldapMappingStrategy(properties, Clock.systemUTC(),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(dev.starryeye.organization.ldap.strategy.SkippedEntryObserver.class));
        return new LdapDirectoryAccountSource(LdapConfig.시작_주소별_템플릿(properties, 첫_템플릿), strategy, 전체, 주소당);
    }

    private static String 주소(InMemoryDirectoryServer 서버) {
        return EmbeddedLdapListener.접속_주소(서버);
    }

    private InMemoryDirectoryServer 서버(String 비밀번호, InMemoryOperationInterceptor... 가로채기) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, 비밀번호);
        config.setListenerConfigs(EmbeddedLdapListener.리스너("test"));
        config.setSchema(null);
        for (InMemoryOperationInterceptor 가로챈다 : 가로채기) {
            config.addInMemoryOperationInterceptor(가로챈다);
        }
        var server = new InMemoryDirectoryServer(config);
        server.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(LDIF.getBytes(StandardCharsets.UTF_8))));
        server.startListening();
        서버들.add(server);
        return server;
    }

    /** 검색마다 BUSY 로 거절한다. */
    private static final class 검색을_바쁘게_거절한다 extends InMemoryOperationInterceptor {

        @Override
        public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
            throw new LDAPException(ResultCode.BUSY, "바쁘다(시험)");
        }
    }

    /** 검색마다 정해진 시간 잔다 — 시험이 서버를 내릴 때 풀린다(2초 이하로 둔다). */
    private static final class 검색에서_잔다 extends InMemoryOperationInterceptor {

        private final Duration 시간;

        검색에서_잔다(Duration 시간) {
            this.시간 = 시간;
        }

        @Override
        public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
            try {
                Thread.sleep(시간.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 서버가 받은 바인드 요청 수. */
    private static final class 바인드를_센다 extends InMemoryOperationInterceptor {

        private final AtomicInteger 수 = new AtomicInteger();

        @Override
        public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) {
            수.incrementAndGet();
        }

        int 횟수() {
            return 수.get();
        }
    }
}

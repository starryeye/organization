package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    private static final String 끝남_문구 = "한 사람 읽기가 일시 장애로 끝났다";
    private static final String 넘김_문구 = "한 사람 읽기를 다음 주소로 넘긴다";

    private final List<InMemoryDirectoryServer> 서버들 = new ArrayList<>();
    private ListAppender<ILoggingEvent> 로그;
    private Level 이전_수준;

    @BeforeEach
    void 로그를_잡는다() {
        로그 = new ListAppender<>();
        로그.start();
        Logger logger = (Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap");
        이전_수준 = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(로그);
    }

    @AfterEach
    void 서버를_내린다() {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap");
        logger.detachAppender(로그);
        logger.setLevel(이전_수준);
        로그.stop();
        서버들.forEach(서버 -> 서버.shutDown(true));
    }

    private List<String> 경고들() {
        return 로그.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> 경고들(String 문구) {
        return 경고들().stream().filter(message -> message.contains(문구)).toList();
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
    // 깨뜨릴 변경: 다 쓴 뒤 일시 장애를 DirectoryReadException 으로 바꾸거나, 끝났다는 WARN(주소 수·원인 클래스)·넘길 때 실패한 주소와 원인을 빼거나, 직원 id·DN 을 로그에 실으면 이 시험이 깨진다
    void 모든_주소가_바쁘면_DirectoryUnavailableException() throws Exception {
        // given
        var A = 서버(BIND_PASSWORD, new 검색을_바쁘게_거절한다());
        var B = 서버(BIND_PASSWORD, new 검색을_바쁘게_거절한다());
        var source = 원천(List.of(주소(A), 주소(B)), Duration.ofSeconds(30), Duration.ofSeconds(10));

        // when, then
        assertThatThrownBy(() -> source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30)))
                .isInstanceOf(DirectoryUnavailableException.class);
        assertThat(경고들(끝남_문구)).singleElement().satisfies(경고 ->
                assertThat(경고).contains("시도한 주소 2개").contains("ServiceUnavailableException"));
        assertThat(경고들(넘김_문구)).singleElement().satisfies(경고 ->
                assertThat(경고).contains(주소(A)).contains(주소(B)).contains("ServiceUnavailableException"));
        assertThat(경고들()).allSatisfy(경고 -> assertThat(경고).doesNotContain("kim").doesNotContain(KIM_DN));
    }

    @Test
    @DisplayName("주소 상한을 넘으면 다음 주소로 넘긴다")
    // 깨뜨릴 변경: 시도마다 건 timeout(주소당) 을 빼면 A 가 5초 뒤 제 답(BLOCKED)을 내 이 시험이 깨진다
    void 주소_상한을_넘으면_다음_주소로_넘긴다() throws Exception {
        // given — A 는 5초를 자고 나면 BLOCKED 로 답하고, B 는 ACTIVE 로 답한다. 주소 상한은 1초라 B 는 넉넉하다
        var A = 잠금_서버(new 검색에서_잔다(Duration.ofSeconds(5)));
        var B = 서버(BIND_PASSWORD);
        var source = 원천(List.of(주소(A), 주소(B)), Duration.ofSeconds(10), Duration.ofSeconds(1));

        // when
        var 상태 = source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30));

        // then
        assertThat(상태).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    @DisplayName("전체 상한을 넘으면 DirectoryUnavailableException 이다")
    // 깨뜨릴 변경: 바깥 timeout(전체) 을 빼면 A·B 가 주소 상한에서 넘어가 C 가 ACTIVE 로 답하고, 전체 상한의 WARN 을 빼거나 직원 id 를 실으면 이 시험이 깨진다
    void 전체_상한을_넘으면_DirectoryUnavailableException() throws Exception {
        // given — 셋째(C)가 멀쩡해, 전체 상한이 없으면 A·B 가 1초씩 지나 넘어가 C 가 ACTIVE 로 답한다
        var A = 서버(BIND_PASSWORD, new 검색에서_잔다(Duration.ofSeconds(5)));
        var B = 서버(BIND_PASSWORD, new 검색에서_잔다(Duration.ofSeconds(5)));
        var C = 서버(BIND_PASSWORD);
        var source = 원천(List.of(주소(A), 주소(B), 주소(C)), Duration.ofMillis(1500), Duration.ofSeconds(1));

        // when, then
        assertThatThrownBy(() -> source.accountState("kim", KIM_DN).block(Duration.ofSeconds(30)))
                .isInstanceOf(DirectoryUnavailableException.class);
        assertThat(경고들(끝남_문구)).singleElement().satisfies(경고 ->
                assertThat(경고).contains("시도한 주소").contains("TimeoutException"));
        assertThat(경고들()).allSatisfy(경고 -> assertThat(경고).doesNotContain("kim").doesNotContain(KIM_DN));
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
        return 서버(LDIF, 비밀번호, 가로채기);
    }

    /** kim 이 영구 잠금인 그림을 내는 서버 — 활성인 서버와 답이 갈린다. */
    private InMemoryDirectoryServer 잠금_서버(InMemoryOperationInterceptor... 가로채기) throws Exception {
        String 잠긴_ldif = LDIF.replace("sn: Kim\n", "sn: Kim\npwdAccountLockedTime: 000001010000Z\n");
        assertThat(잠긴_ldif).as("kim 엔트리에 잠금 속성이 붙어야 한다").contains("pwdAccountLockedTime");
        return 서버(잠긴_ldif, BIND_PASSWORD, 가로채기);
    }

    private InMemoryDirectoryServer 서버(String ldif, String 비밀번호, InMemoryOperationInterceptor... 가로채기) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, 비밀번호);
        config.setListenerConfigs(EmbeddedLdapListener.리스너("test"));
        config.setSchema(null);
        for (InMemoryOperationInterceptor 가로챈다 : 가로채기) {
            config.addInMemoryOperationInterceptor(가로챈다);
        }
        var server = new InMemoryDirectoryServer(config);
        server.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8))));
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

    /** 검색마다 정해진 시간 잔다(2초 이하로 둔다) — 잔 뒤에는 검색이 그대로 진행된다. */
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

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
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
import dev.starryeye.organization.ldap.fixture.SilentLdapServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.naming.Context;
import javax.naming.directory.DirContext;
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
 * 도메인 컨트롤러 넘김(설계 2026-11-14 F1~F4) — 주소 목록은 JNDI 가 새 커넥션마다 앞에서부터 시도한다. 연결 거절·접속 한도 초과는 다음 주소로 넘어가고,
 * 인증 실패(49)는 넘기지 않는다(JNDI 자체 동작 — 앱은 바꾸지 않는다).
 */
class LdapFailoverTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final String BIND_DN = "cn=admin," + BASE_DN;
    private static final String BIND_PASSWORD = "adminpassword";

    /** 직원 하나·조직 하나 — 서버 B 가 내는 그림. */
    private static final String LDIF = """
            dn: dc=example,dc=com
            objectClass: top
            objectClass: domain
            dc: example

            dn: ou=people,dc=example,dc=com
            objectClass: organizationalUnit
            ou: people

            dn: ou=groups,dc=example,dc=com
            objectClass: organizationalUnit
            ou: groups

            dn: uid=kim,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: kim
            cn: Kim Chulsoo
            sn: Kim
            displayName: 김철수
            mail: kim@example.com

            dn: cn=DEV001,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV001
            description: 개발본부
            member: uid=kim,ou=people,dc=example,dc=com
            """;

    private final List<InMemoryDirectoryServer> 서버들 = new ArrayList<>();

    private final ListAppender<ILoggingEvent> 로그 = new ListAppender<>();
    private final Logger 템플릿_로거 = (Logger) LoggerFactory.getLogger(LdapTemplates.class);
    private final ListAppender<ILoggingEvent> 원천_로그 = new ListAppender<>();
    private final Logger 원천_로거 = (Logger) LoggerFactory.getLogger(LdapDirectorySnapshotSource.class);

    @BeforeEach
    void 로그를_붙인다() {
        로그.start();
        템플릿_로거.addAppender(로그);
        원천_로그.start();
        원천_로거.addAppender(원천_로그);
    }

    @AfterEach
    void 서버를_내린다() {
        템플릿_로거.detachAppender(로그);
        원천_로거.detachAppender(원천_로그);
        서버들.forEach(서버 -> 서버.shutDown(true));
    }

    @Test
    @DisplayName("주소 목록이 컨텍스트 소스에 순서대로 실린다")
    void 주소_목록이_컨텍스트_소스에_순서대로_실린다() {
        var properties = 설정(List.of("ldap://dc1:389", "ldaps://dc2:636"));

        LdapContextSource contextSource = new LdapConfig().ldapContextSource(properties);

        assertThat(contextSource.getUrls()).containsExactly("ldap://dc1:389", "ldaps://dc2:636");
    }

    @Test
    @DisplayName("주소 앞뒤의 빈칸은 떼고 싣는다 — 따옴표로 감싼 yml 항목도 JNDI 에는 주소만 간다")
    void 주소_앞뒤의_빈칸은_떼고_싣는다() {
        var properties = 설정(List.of(" ldap://dc1:389 "));

        LdapContextSource contextSource = new LdapConfig().ldapContextSource(properties);

        assertThat(contextSource.getUrls()).containsExactly("ldap://dc1:389");
    }

    @Test
    @DisplayName("첫 주소가 연결을 거절하면 둘째 주소에서 그림을 읽는다")
    void 첫_주소가_연결을_거절하면_둘째_주소에서_그림을_읽는다() throws Exception {
        var B = 서버(BIND_PASSWORD);

        DirectorySnapshot 그림 = 그림을_읽는다(설정(List.of(SilentLdapServer.닫힌_주소(), EmbeddedLdapListener.접속_주소(B))));

        assertThat(그림.users()).containsOnlyKeys("kim");
        assertThat(그림.groups()).containsOnlyKeys("DEV001");
    }

    @Test
    @DisplayName("첫 주소가 접속만 받고 답하지 않으면 접속 한도 뒤 둘째에서 읽는다")
    void 첫_주소가_접속만_받고_답하지_않으면_접속_한도_뒤_둘째에서_읽는다() throws Exception {
        var B = 서버(BIND_PASSWORD);
        try (SilentLdapServer 침묵 = SilentLdapServer.연다()) {
            var properties = 설정(List.of(침묵.접속_주소(), EmbeddedLdapListener.접속_주소(B)));
            properties.setConnectTimeout(Duration.ofMillis(500));

            DirectorySnapshot 그림 = 그림을_읽는다(properties);

            assertThat(그림.users()).containsOnlyKeys("kim");
            assertThat(그림.groups()).containsOnlyKeys("DEV001");
        }
    }

    @Test
    @DisplayName("첫 주소가 인증을 거절하면 둘째로 넘기지 않는다")
    void 첫_주소가_인증을_거절하면_둘째로_넘기지_않는다() throws Exception {
        var A바인드 = new 바인드를_센다();
        var B바인드 = new 바인드를_센다();
        var A = 서버("다른비밀번호", A바인드);
        var B = 서버(BIND_PASSWORD, B바인드);
        var properties = 설정(List.of(EmbeddedLdapListener.접속_주소(A), EmbeddedLdapListener.접속_주소(B)));
        properties.setMaxRetries(3);

        var source = 원천을_만든다(properties);

        assertThatThrownBy(() -> source.fetchAll().block(Duration.ofSeconds(30)))
                .isInstanceOf(org.springframework.ldap.AuthenticationException.class);
        assertThat(A바인드.횟수()).as("인증 실패는 재시도하지 않는다").isEqualTo(1);
        assertThat(B바인드.횟수()).as("인증 실패는 다음 주소로 넘기지 않는다").isZero();
    }

    @Test
    @DisplayName("넘긴 커넥션의 붙은 주소는 둘째 주소다")
    void 넘긴_커넥션의_붙은_주소는_둘째_주소다() throws Exception {
        var B = 서버(BIND_PASSWORD);
        var 주소B = EmbeddedLdapListener.접속_주소(B);
        LdapContextSource contextSource = 컨텍스트_소스(설정(List.of(SilentLdapServer.닫힌_주소(), 주소B)));

        DirContext ctx = contextSource.getReadOnlyContext();
        try {
            // F7 이 기대는 JDK provider 동작: 접속한 URL 로 java.naming.provider.url 을 덮어쓴다 (목록 전체가 아니라 붙은 주소 하나)
            assertThat(ctx.getEnvironment().get(Context.PROVIDER_URL)).asString().doesNotContain(" ").isEqualTo(주소B + "/" + BASE_DN);
            assertThat(LdapUrls.붙은_주소(ctx)).contains(LdapUrls.주소_부분(주소B).orElseThrow());
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("넘기지 않은 커넥션의 붙은 주소는 첫 주소다")
    void 넘기지_않은_커넥션의_붙은_주소는_첫_주소다() throws Exception {
        var B = 서버(BIND_PASSWORD);
        var 주소B = EmbeddedLdapListener.접속_주소(B);
        LdapContextSource contextSource = 컨텍스트_소스(설정(List.of(주소B, SilentLdapServer.닫힌_주소())));

        DirContext ctx = contextSource.getReadOnlyContext();
        try {
            assertThat(LdapUrls.붙은_주소(ctx)).contains(LdapUrls.주소_부분(주소B).orElseThrow());
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("넘긴 커넥션마다 WARN 한 줄을 남긴다")
    void 넘긴_커넥션마다_WARN_한_줄을_남긴다() throws Exception {
        var B = 서버(BIND_PASSWORD);
        var 주소B = EmbeddedLdapListener.접속_주소(B);
        String 닫힌 = SilentLdapServer.닫힌_주소();

        그림을_읽는다(설정(List.of(닫힌, 주소B)));

        var 경고들 = 경고들();
        assertThat(경고들).hasSizeGreaterThanOrEqualTo(2);
        assertThat(경고들).allSatisfy(메시지 -> assertThat(메시지)
                .contains(LdapUrls.주소_부분(주소B).orElseThrow())
                .contains(LdapUrls.주소_부분(닫힌).orElseThrow()));
    }

    @Test
    @DisplayName("페이징을 끈 검색(ldap.page-size=0)도 넘긴 커넥션에 WARN 을 남긴다")
    void 페이징을_끈_검색도_넘긴_커넥션에_WARN_을_남긴다() throws Exception {
        var B = 서버(BIND_PASSWORD);
        var 주소B = EmbeddedLdapListener.접속_주소(B);
        String 닫힌 = SilentLdapServer.닫힌_주소();
        var properties = 설정(List.of(닫힌, 주소B));
        properties.setPageSize(0);

        DirectorySnapshot 그림 = 그림을_읽는다(properties);

        assertThat(그림.users()).containsOnlyKeys("kim");
        assertThat(그림.groups()).containsOnlyKeys("DEV001");
        assertThat(경고들()).isNotEmpty().allSatisfy(메시지 -> assertThat(메시지)
                .contains(LdapUrls.주소_부분(주소B).orElseThrow())
                .contains(LdapUrls.주소_부분(닫힌).orElseThrow()));
    }

    @Test
    @DisplayName("주소가 하나면 WARN 이 없다")
    void 주소가_하나면_WARN_이_없다() throws Exception {
        var B = 서버(BIND_PASSWORD);

        그림을_읽는다(설정(List.of(EmbeddedLdapListener.접속_주소(B))));

        assertThat(경고들()).isEmpty();
    }

    @Test
    @DisplayName("첫 주소가 바인드는 받고 검색을 실패시키면 재시도가 둘째 주소부터 읽는다")
    void 첫_주소가_바인드는_받고_검색을_실패시키면_재시도가_둘째_주소부터_읽는다() throws Exception {
        var A바인드 = new 바인드를_센다();
        var A검색 = new 검색을_바쁘게_거절한다();
        var A = 서버(BIND_PASSWORD, A바인드, A검색);
        var B = 서버(BIND_PASSWORD);
        var properties = 설정(List.of(EmbeddedLdapListener.접속_주소(A), EmbeddedLdapListener.접속_주소(B)));
        properties.setMaxRetries(3);

        DirectorySnapshot 그림 = 그림을_읽는다(properties);

        assertThat(그림.users()).containsOnlyKeys("kim");
        assertThat(그림.groups()).containsOnlyKeys("DEV001");
        assertThat(A검색.횟수()).as("재시도는 둘째 주소부터라 A 는 검색을 한 번만 받는다").isEqualTo(1);
        assertThat(A바인드.횟수()).isEqualTo(1);
        assertThat(경고들()).as("돌린 시도는 제 목록의 첫 주소(B)에 붙으므로 접속 단계 넘김 WARN(F7 a)이 없다").isEmpty();
    }

    @Test
    @DisplayName("다음 회차는 다시 설정 순서부터다")
    void 다음_회차는_다시_설정_순서부터다() throws Exception {
        var A바인드 = new 바인드를_센다();
        var A = 서버(BIND_PASSWORD, A바인드, new 검색을_바쁘게_거절한다());
        var B = 서버(BIND_PASSWORD);
        var C = 서버(BIND_PASSWORD);
        // 셋이어야 한다 — 둘이면 시도 수를 회차 사이에 이어 세어도(k=2 → 2 % 2 = 0) A 로 돌아와 같은 값이 나온다
        var properties = 설정(List.of(EmbeddedLdapListener.접속_주소(A), EmbeddedLdapListener.접속_주소(B), EmbeddedLdapListener.접속_주소(C)));
        properties.setMaxRetries(3);
        var source = 원천을_만든다(properties);

        source.fetchAll().block(Duration.ofSeconds(30));
        DirectorySnapshot 둘째 = source.fetchAll().block(Duration.ofSeconds(30));

        assertThat(둘째.users()).containsOnlyKeys("kim");
        assertThat(A바인드.횟수()).as("다음 회차는 시도 수를 이어 세지 않고 설정 순서(A)부터 다시 시작한다").isEqualTo(2);
    }

    @Test
    @DisplayName("다음 주소부터 읽는 재시도는 시작 주소를 WARN 에 적는다")
    void 다음_주소부터_읽는_재시도는_시작_주소를_WARN_에_적는다() throws Exception {
        var A = 서버(BIND_PASSWORD, new 검색을_바쁘게_거절한다());
        var B = 서버(BIND_PASSWORD);
        var 주소B = EmbeddedLdapListener.접속_주소(B);
        var properties = 설정(List.of(EmbeddedLdapListener.접속_주소(A), 주소B));
        properties.setMaxRetries(3);

        그림을_읽는다(properties);

        var 시작_경고들 = 원천_로그.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(메시지 -> 메시지.contains("다음 주소부터"))
                .toList();
        assertThat(시작_경고들).hasSize(1);
        assertThat(시작_경고들.get(0)).contains(LdapUrls.주소_부분(주소B).orElseThrow());
    }

    @Test
    @DisplayName("모든 주소가 거절하면 재시도를 다 쓰고 마지막 실패를 던진다")
    void 모든_주소가_거절하면_재시도를_다_쓰고_마지막_실패를_던진다() throws Exception {
        var properties = 설정(List.of(SilentLdapServer.닫힌_주소(), SilentLdapServer.닫힌_주소()));
        properties.setMaxRetries(2);
        var source = 원천을_만든다(properties);

        assertThatThrownBy(() -> source.fetchAll().block(Duration.ofSeconds(30)))
                .as("마지막 실패를 그대로 던진다 — RetryExhaustedException 이 아니다")
                .isInstanceOf(org.springframework.ldap.CommunicationException.class)
                .matches(e -> !reactor.core.Exceptions.isRetryExhausted(e));
    }

    private List<String> 경고들() {
        return 로그.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static LdapContextSource 컨텍스트_소스(LdapProperties properties) throws Exception {
        LdapContextSource contextSource = new LdapConfig().ldapContextSource(properties);
        contextSource.afterPropertiesSet();
        return contextSource;
    }

    private static LdapProperties 설정(List<String> 주소들) {
        var properties = EmbeddedLdapSupport.이름기반();
        properties.setUrls(주소들);
        properties.setBindDn(BIND_DN);
        properties.setBindPassword(BIND_PASSWORD);
        properties.setRetryMinBackoff(Duration.ofMillis(10));
        properties.setRetryMaxBackoff(Duration.ofMillis(50));
        return properties;
    }

    /** 운영과 같이 {@link LdapConfig} 로 만든 원천. */
    private static LdapDirectorySnapshotSource 원천을_만든다(LdapProperties properties) throws Exception {
        LdapConfig config = new LdapConfig();
        LdapContextSource contextSource = config.ldapContextSource(properties);
        contextSource.afterPropertiesSet();
        return config.ldapDirectorySnapshotSource(
                config.ldapTemplate(contextSource),
                config.ldapMappingStrategy(properties, Clock.systemUTC(),
                        new org.springframework.beans.factory.support.StaticListableBeanFactory()
                                .getBeanProvider(dev.starryeye.organization.ldap.strategy.SkippedEntryObserver.class)),
                properties);
    }

    private static DirectorySnapshot 그림을_읽는다(LdapProperties properties) throws Exception {
        return 원천을_만든다(properties).fetchAll().block(Duration.ofSeconds(30));
    }

    /** 작은 LDIF 를 내는 임베디드 서버. 바인드 비밀번호만 서버마다 다르게 줄 수 있다. */
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

    /** 검색마다 BUSY 로 거절하고 받은 검색 수를 센다 — 바인드는 받고 검색에서 실패하는 DC. */
    private static final class 검색을_바쁘게_거절한다 extends InMemoryOperationInterceptor {

        private final AtomicInteger 수 = new AtomicInteger();

        @Override
        public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
            수.incrementAndGet();
            throw new LDAPException(ResultCode.BUSY, "바쁘다(시험)");
        }

        int 횟수() {
            return 수.get();
        }
    }

    /** 서버가 받은 바인드 요청 수. */
    private static final class 바인드를_센다 extends InMemoryOperationInterceptor {

        private final AtomicInteger 수 = new AtomicInteger();

        @Override
        public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) throws LDAPException {
            수.incrementAndGet();
        }

        int 횟수() {
            return 수.get();
        }
    }
}

package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import dev.starryeye.organization.ldap.LdapConfig;
import dev.starryeye.organization.ldap.LdapProperties;
import dev.starryeye.organization.ldap.LdapUrls;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
import dev.starryeye.organization.ldap.fixture.SilentLdapServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.ldap.CommunicationException;
import org.springframework.ldap.core.support.LdapContextSource;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.naming.directory.DirContext;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LDAP 헬스 인디케이터 (설계 §12.3).
 *
 * <p>이 앱의 파이프라인은 LDAP 읽기에서 시작한다. 그것이 끊겼는데 UP 을 보고하면
 * 헬스체크가 거짓말을 하는 셈이다. 하루 1회 스케줄이라 다음 실행까지 24시간이 비므로
 * 그 사이 이상을 알아챌 수단이 이것뿐이다.
 */
class LdapHealthIndicatorTest {

    private static LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setUrls(List.of("ldap://localhost:1389"));
        properties.setBaseDn("dc=example,dc=com");
        properties.setStrategy("group-of-names");
        return properties;
    }

    /** close() 만 받아주면 되는 컨텍스트. DirContext 는 메서드가 많아 프록시로 만든다. */
    private static DirContext 아무것도_안_하는_컨텍스트() {
        return (DirContext) Proxy.newProxyInstance(
                LdapHealthIndicatorTest.class.getClassLoader(),
                new Class<?>[]{DirContext.class},
                (proxy, method, args) -> null);
    }

    /** 지정한 방식으로만 응답하는 ContextSource. 나머지 동작은 건드리지 않는다. */
    private static class 가짜ContextSource extends LdapContextSource {

        private final Runnable 동작;
        final AtomicInteger 호출수 = new AtomicInteger();

        가짜ContextSource(Runnable 동작) {
            this.동작 = 동작;
        }

        @Override
        public DirContext getReadOnlyContext() {
            호출수.incrementAndGet();
            동작.run();
            return 아무것도_안_하는_컨텍스트();
        }
    }

    /** 테스트가 시각을 옮기는 시계. */
    private static final class 움직이는_시계 extends Clock {

        private Instant 지금 = Instant.parse("2026-10-05T00:00:00Z");

        void 옮긴다(Duration 만큼) {
            지금 = 지금.plus(만큼);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return 지금;
        }
    }

    @Test
    @DisplayName("LDAP 이 응답하지 않으면 함께 매달리지 않고 DOWN 을 보고한다")
    void 매달리지_않고_DOWN을_보고한다() {
        // given — 영원히 돌아오지 않는 바인드. 타임아웃이 없으면 이 테스트가 끝나지 않는다
        var 걸쇠 = new CountDownLatch(1);
        var source = new 가짜ContextSource(() -> {
            try {
                걸쇠.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var indicator = new LdapHealthIndicator(source, 설정(), Clock.systemUTC());

        // when — 프로브 상한(2초)보다 넉넉히 기다린다
        Health health = indicator.health().block(Duration.ofSeconds(10));

        // then
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        걸쇠.countDown();
    }

    @Test
    @DisplayName("LDAP 연결이 끊겨 있으면 DOWN 을 보고한다")
    void 연결이_끊기면_DOWN이다() {
        // given
        var source = new 가짜ContextSource(() -> {
            throw new CommunicationException(new javax.naming.CommunicationException("연결 거부(테스트)"));
        });
        var indicator = new LdapHealthIndicator(source, 설정(), Clock.systemUTC());

        // when
        Health health = indicator.health().block(Duration.ofSeconds(10));

        // then
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("LDAP 이 제때 바인드되면 UP 과 접속 정보를 보고한다")
    void 제때_바인드되면_UP이다() {
        // given
        var source = new 가짜ContextSource(() -> {
        });
        var indicator = new LdapHealthIndicator(source, 설정(), Clock.systemUTC());

        // when
        Health health = indicator.health().block(Duration.ofSeconds(10));

        // then
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("urls", List.of("ldap://localhost:1389"))
                .containsEntry("baseDn", "dc=example,dc=com")
                .containsEntry("strategy", "group-of-names");
        // 검색이 아니라 바인드만 한다 — 디렉터리 크기와 무관한 비용이어야 한다
        assertThat(source.호출수).hasValue(1);
    }

    @Test
    @DisplayName("인증 실패 뒤 30분 동안은 다시 바인드하지 않고 DOWN 을 답한다 — 프로브가 서비스 계정을 잠그지 않게(점검 S23)")
    void 인증_실패_뒤에는_바인드를_쉰다() {
        // given
        var 시계 = new 움직이는_시계();
        var source = new 가짜ContextSource(() -> {
            throw new org.springframework.ldap.AuthenticationException(new javax.naming.AuthenticationException("49"));
        });
        var indicator = new LdapHealthIndicator(source, 설정(), 시계);
        indicator.health().block();

        // when
        시계.옮긴다(Duration.ofMinutes(29));
        Health 쉬는_동안 = indicator.health().block();

        // then — 기억한 인증 오류와 다음 확인 시각을 싣는다
        assertThat(쉬는_동안.getStatus()).isEqualTo(Status.DOWN);
        assertThat(쉬는_동안.getDetails())
                .containsEntry("다음 확인", "2026-10-05T00:30:00Z")
                .hasEntrySatisfying("error", error -> assertThat(error).asString()
                        .contains(org.springframework.ldap.AuthenticationException.class.getName()));
        assertThat(source.호출수).hasValue(1);
    }

    @Test
    @DisplayName("쉼은 health() 를 부를 때가 아니라 구독할 때 판정한다 — 인증 실패 전에 만든 프로브도 실패 뒤에 구독하면 바인드하지 않는다")
    void 쉼은_구독할_때_판정한다() {
        // given — 인증 실패를 기억하기 전에 프로브를 만들어 둔다
        var 시계 = new 움직이는_시계();
        var source = new 가짜ContextSource(() -> {
            throw new org.springframework.ldap.AuthenticationException(new javax.naming.AuthenticationException("49"));
        });
        var indicator = new LdapHealthIndicator(source, 설정(), 시계);
        Mono<Health> 먼저_만든_프로브 = indicator.health();
        indicator.health().block();

        // when
        Health 답 = 먼저_만든_프로브.block();

        // then
        assertThat(답.getStatus()).isEqualTo(Status.DOWN);
        assertThat(source.호출수).hasValue(1);
    }

    @Test
    @DisplayName("30분이 지나면 한 번 다시 바인드하고, 비밀번호를 고쳤으면 UP 으로 돌아온다")
    void 쉼이_끝나면_다시_확인한다() {
        // given
        var 시계 = new 움직이는_시계();
        var 실패 = new AtomicBoolean(true);
        var source = new 가짜ContextSource(() -> {
            if (실패.get()) {
                throw new org.springframework.ldap.AuthenticationException(new javax.naming.AuthenticationException("49"));
            }
        });
        var indicator = new LdapHealthIndicator(source, 설정(), 시계);
        indicator.health().block();
        실패.set(false);

        // when
        시계.옮긴다(Duration.ofMinutes(30));
        Health 다시 = indicator.health().block();

        // then
        assertThat(다시.getStatus()).isEqualTo(Status.UP);
        assertThat(source.호출수).hasValue(2);
    }

    @Test
    @DisplayName("인증이 아닌 실패(연결 끊김)는 쉬지 않고 매번 다시 확인한다")
    void 인증이_아닌_실패는_쉬지_않는다() {
        // given
        var 시계 = new 움직이는_시계();
        var source = new 가짜ContextSource(() -> {
            throw new org.springframework.ldap.CommunicationException(new javax.naming.CommunicationException("reset"));
        });
        var indicator = new LdapHealthIndicator(source, 설정(), 시계);
        indicator.health().block();

        // when
        indicator.health().block();

        // then
        assertThat(source.호출수).hasValue(2);
    }

    // ---- 운영과 같은 컨텍스트 소스로, 주소 목록을 넘기는 점검(설계 2026-11-14 F5) ----

    private static final String 베이스 = "dc=example,dc=com";
    private static final String 바인드_DN = "cn=admin," + 베이스;
    private static final String 바인드_비밀번호 = "adminpassword";

    private final java.util.List<InMemoryDirectoryServer> 서버들 = new java.util.ArrayList<>();

    @AfterEach
    void 서버를_내린다() {
        서버들.forEach(서버 -> 서버.shutDown(true));
    }

    private InMemoryDirectoryServer 서버(String 비밀번호, InMemoryOperationInterceptor... 가로채기) throws Exception {
        var config = new InMemoryDirectoryServerConfig(베이스);
        config.addAdditionalBindCredentials(바인드_DN, 비밀번호);
        config.setListenerConfigs(EmbeddedLdapListener.리스너("health"));
        config.setSchema(null);
        for (InMemoryOperationInterceptor 가로챈다 : 가로채기) {
            config.addInMemoryOperationInterceptor(가로챈다);
        }
        var server = new InMemoryDirectoryServer(config);
        server.startListening();
        서버들.add(server);
        return server;
    }

    private static LdapHealthIndicator 운영과_같은_인디케이터(List<String> 주소들) throws Exception {
        var properties = 설정();
        properties.setUrls(주소들);
        properties.setBindDn(바인드_DN);
        properties.setBindPassword(바인드_비밀번호);
        var contextSource = new LdapConfig().ldapContextSource(properties);
        contextSource.afterPropertiesSet();
        return new LdapHealthIndicator(contextSource, properties, Clock.systemUTC());
    }

    @Test
    @DisplayName("첫 주소가 거절하고 둘째가 바인드되면 UP 이고 붙은 주소가 둘째다")
    void 첫_주소가_거절하고_둘째가_바인드되면_UP_이고_붙은_주소가_둘째다() throws Exception {
        var B = 서버(바인드_비밀번호);
        var 주소들 = List.of(SilentLdapServer.닫힌_주소(), EmbeddedLdapListener.접속_주소(B));

        Health health = 운영과_같은_인디케이터(주소들).health().block(Duration.ofSeconds(10));

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("connectedUrl", LdapUrls.주소_부분(주소들.get(1)).orElseThrow())
                .containsEntry("urls", 주소들);
    }

    @Test
    @DisplayName("첫 주소가 답하지 않으면 2초 안에 DOWN 이다")
    void 첫_주소가_답하지_않으면_2초_안에_DOWN_이다() throws Exception {
        var B = 서버(바인드_비밀번호);
        try (SilentLdapServer 침묵 = SilentLdapServer.연다()) {
            var indicator = 운영과_같은_인디케이터(List.of(침묵.접속_주소(), EmbeddedLdapListener.접속_주소(B)));

            // 프로브 상한(2초)이 접속 한도(기본 10초)보다 짧아 DOWN 이다 — 시간은 단언하지 않는다
            Health health = indicator.health().block(Duration.ofSeconds(10));

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }
    }

    @Test
    @DisplayName("인증 실패면 프로브 하나에 틀린 바인드는 한 번이다")
    void 인증_실패면_프로브_하나에_틀린_바인드는_한_번이다() throws Exception {
        var A바인드 = new 바인드를_센다();
        var B바인드 = new 바인드를_센다();
        var A = 서버("다른비밀번호", A바인드);
        var B = 서버("다른비밀번호", B바인드);
        var indicator = 운영과_같은_인디케이터(List.of(EmbeddedLdapListener.접속_주소(A), EmbeddedLdapListener.접속_주소(B)));

        Health health = indicator.health().block(Duration.ofSeconds(10));

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(A바인드.횟수() + B바인드.횟수()).as("주소마다 바인드하면 틀린 비밀번호가 DC 수만큼 쌓여 잠긴다").isEqualTo(1);
    }

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

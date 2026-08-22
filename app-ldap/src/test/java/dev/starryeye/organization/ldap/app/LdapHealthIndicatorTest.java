package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.ldap.LdapProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.ldap.CommunicationException;
import org.springframework.ldap.core.support.LdapContextSource;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
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
        properties.setUrl("ldap://localhost:1389");
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
        var indicator = new LdapHealthIndicator(source, 설정());

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
        var indicator = new LdapHealthIndicator(source, 설정());

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
        var indicator = new LdapHealthIndicator(source, 설정());

        // when
        Health health = indicator.health().block(Duration.ofSeconds(10));

        // then
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("url", "ldap://localhost:1389")
                .containsEntry("baseDn", "dc=example,dc=com")
                .containsEntry("strategy", "group-of-names");
        // 검색이 아니라 바인드만 한다 — 디렉터리 크기와 무관한 비용이어야 한다
        assertThat(source.호출수).hasValue(1);
    }
}

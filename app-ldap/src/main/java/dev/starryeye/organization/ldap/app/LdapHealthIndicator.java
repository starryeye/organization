package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.ldap.LdapProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * LDAP 연결을 확인한다 (설계 §12.3).
 *
 * <p>이 앱의 파이프라인은 LDAP 읽기에서 시작한다. DynamoDB 와 OpenFGA 만 보고 UP 을
 * 보고하면 <b>정작 첫 단추가 끊겼는데 건강하다고 답하는</b> 상태가 된다. 하루 1회
 * 스케줄이라 다음 실행까지 24시간이 비므로, 그 사이 이상을 알아챌 수단이 헬스체크뿐이다.
 */
@Component("ldap")
@RequiredArgsConstructor
public class LdapHealthIndicator implements ReactiveHealthIndicator {

    /**
     * 프로브가 응답 없이 매달리지 않게 상한을 둔다. 형제 인디케이터와 같은 값을 쓴다 —
     * 매달린 프로브는 죽은 것보다 나쁘다. 오케스트레이터는 DOWN 을 보면 재시작하지만,
     * 아무 답도 없으면 그 판단조차 못 한다.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final LdapContextSource contextSource;
    private final LdapProperties properties;

    /**
     * <b>검색이 아니라 연결/바인드만 확인한다.</b> 엔트리를 훑으면 디렉터리 크기에 따라
     * 프로브 비용이 커지고, 헬스체크가 LDAP 서버에 부담을 주는 본말전도가 된다.
     * {@code getReadOnlyContext()} 는 바인드까지 수행하므로 자격증명 문제도 함께 잡힌다.
     *
     * <p>LDAP 은 블로킹이라 {@code boundedElastic} 으로 격리한다 — 이벤트 루프에서
     * 직접 부르면 프로브 하나가 애플리케이션 전체를 멈춘다.
     */
    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() -> {
                    contextSource.getReadOnlyContext().close();
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ignored -> Health.up()
                        .withDetail("url", properties.getUrl())
                        .withDetail("baseDn", properties.getBaseDn())
                        .withDetail("strategy", properties.getStrategy())
                        .build())
                .timeout(PROBE_TIMEOUT)
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}

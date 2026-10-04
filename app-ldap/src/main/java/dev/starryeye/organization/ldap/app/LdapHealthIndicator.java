package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.ldap.LdapProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LDAP 연결을 확인한다 (설계 §12.3).
 *
 * <p>이 앱의 파이프라인은 LDAP 읽기에서 시작한다. DynamoDB 와 OpenFGA 만 보고 UP 을
 * 보고하면 <b>정작 첫 단추가 끊겼는데 건강하다고 답하는</b> 상태가 된다. 하루 1회
 * 스케줄이라 다음 실행까지 24시간이 비므로, 그 사이 이상을 알아챌 수단이 헬스체크뿐이다.
 *
 * <p><b>빈 이름이 {@code ldapHealthIndicator} 다.</b> Spring Boot 는 {@code LdapOperations} 빈이 있으면 블로킹 기본 LDAP 인디케이터를 더하는데,
 * {@code ldapHealthIndicator}·{@code ldapHealthContributor} 라는 빈이 있으면 물러난다. 둘 다 헬스 이름이 {@code ldap} 이라, 다른 이름(예:
 * {@code ldap})으로 두면 나중에 등록되는 Boot 의 것이 이것을 덮어 인증 실패 뒤 쉼과 프로브 상한이 돌지 않는다. 헬스 이름은 빈 이름에서
 * {@code HealthIndicator} 를 뗀 {@code ldap} 그대로다.
 */
@Component("ldapHealthIndicator")
@RequiredArgsConstructor
public class LdapHealthIndicator implements ReactiveHealthIndicator {

    /**
     * 프로브가 응답 없이 매달리지 않게 상한을 둔다. 형제 인디케이터와 같은 값을 쓴다 —
     * 매달린 프로브는 죽은 것보다 나쁘다. 오케스트레이터는 DOWN 을 보면 재시작하지만,
     * 아무 답도 없으면 그 판단조차 못 한다.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 인증 실패 뒤 다시 바인드하기까지 쉬는 시간(설계 2026-10-05 §4.3, 점검 S23). 프로브는 몇 초마다 오므로 틀린 비밀번호로 매번 바인드하면 AD 의 잠금 기준
     * (관찰 창 15~30분 안에 여러 번 실패)에 닿아 서비스 계정이 잠긴다 — 동기화까지 멈춘다. 30분에 한 번은 그 기준에 닿지 않고, 비밀번호를 고치면 재시작 없이 UP 으로
     * 돌아온다. 운영에서 바꿀 이유가 보이면 그때 설정으로 올린다.
     */
    static final Duration 인증_실패_뒤_쉼 = Duration.ofMinutes(30);

    private final LdapContextSource contextSource;
    private final LdapProperties properties;
    private final Clock clock;

    /** 마지막 인증 실패. 바인드에 성공하면 지운다. */
    private final AtomicReference<인증실패> 마지막_인증실패 = new AtomicReference<>();

    private record 인증실패(Instant 시각, Throwable 오류) {
    }

    /**
     * <b>검색이 아니라 연결/바인드만 확인한다.</b> 엔트리를 훑으면 디렉터리 크기에 따라
     * 프로브 비용이 커지고, 헬스체크가 LDAP 서버에 부담을 주는 본말전도가 된다.
     * {@code getReadOnlyContext()} 는 바인드까지 수행하므로 자격증명 문제도 함께 잡힌다.
     * 다만 인증이 실패하면 {@link #인증_실패_뒤_쉼} 동안은 바인드를 쉬고 마지막 실패로 DOWN 을 답한다 — 틀린 비밀번호를 프로브가 되풀이하면 서비스 계정이 잠긴다.
     *
     * <p>LDAP 은 블로킹이라 {@code boundedElastic} 으로 격리한다 — 이벤트 루프에서
     * 직접 부르면 프로브 하나가 애플리케이션 전체를 멈춘다.
     */
    @Override
    public Mono<Health> health() {
        // 쉼은 구독할 때 판정한다 — health() 를 부른 때가 아니다
        return Mono.defer(() -> {
            인증실패 기억 = 마지막_인증실패.get();
            if (기억 != null && clock.instant().isBefore(기억.시각().plus(인증_실패_뒤_쉼))) {
                return Mono.just(Health.down(기억.오류())
                        .withDetail("다음 확인", 기억.시각().plus(인증_실패_뒤_쉼).toString())
                        .withDetail("이유", "인증 실패 뒤 서비스 계정 잠금을 막으려고 바인드를 쉰다")
                        .build());
            }
            return 바인드한다();
        });
    }

    private Mono<Health> 바인드한다() {
        return Mono.fromCallable(() -> {
                    contextSource.getReadOnlyContext().close();
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(ignored -> 마지막_인증실패.set(null))
                .map(ignored -> Health.up()
                        .withDetail("url", properties.getUrl())
                        .withDetail("baseDn", properties.getBaseDn())
                        .withDetail("strategy", properties.getStrategy())
                        .build())
                .timeout(PROBE_TIMEOUT)
                .onErrorResume(error -> {
                    if (error instanceof org.springframework.ldap.AuthenticationException) {
                        마지막_인증실패.set(new 인증실패(clock.instant(), error));
                    }
                    return Mono.just(Health.down(error).build());
                });
    }
}

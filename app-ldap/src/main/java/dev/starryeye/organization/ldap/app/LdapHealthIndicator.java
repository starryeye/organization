package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.ldap.LdapProperties;
import dev.starryeye.organization.ldap.LdapUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * LDAP 연결을 확인한다 (설계 §12.3).
 *
 * <p>이 앱의 파이프라인은 LDAP 읽기에서 시작한다. DynamoDB 와 OpenFGA 만 보고 UP 을
 * 보고하면 <b>정작 첫 단추가 끊겼는데 건강하다고 답하는</b> 상태가 된다. 정기 동기화는
 * {@code sync.cron} 주기(기본 매시 정각)로 돌아 다음 실행까지 그 사이가 비므로, 그 사이 이상을 알아챌 수단이 헬스체크다.
 *
 * <p><b>바인드는 한 번에 하나다</b>(설계 2026-11-14 F5). 프로브 상한 뒤에 온 인증 실패도 기억하고, 진행 중인 바인드가 있으면 새로 바인드하지 않는다 — 앞 주소가
 * 답하지 않는 동안 틀린 비밀번호가 프로브마다 다음 DC 에 쌓이지 않게. 상한이 바인드를 끊으면 JNDI 는 그 뒤에도 다음 DC 에 바인드를 보내고, 그 49 는
 * 아무도 받지 않아 쉼이 걸리지 않는다 — 프로브마다 틀린 바인드가 하나씩 늘어 몇 분 만에 서비스 계정이 잠긴다.
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
     * 아무 답도 없으면 그 판단조차 못 한다. 상한은 프로브의 기다림만 끝낸다 — 바인드는 끊지 않는다({@link #health()}).
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

    /** {@link #마지막_인증실패} 와 {@link #진행중} 을 함께 지킨다 — 쉼 판정과 바인드 시작이, 바인드의 결과 기록과 자리 비우기가 각각 한 번에 일어난다. */
    private final Object 자물쇠 = new Object();

    /** 마지막 인증 실패. 바인드에 성공하면 지운다. {@link #자물쇠} 안에서만 읽고 쓴다. */
    private 인증실패 마지막_인증실패;

    /** 진행 중인 바인드의 답. 없으면 null — 바인드 작업이 결과를 적으면서 비운다. {@link #자물쇠} 안에서만 읽고 쓴다. */
    private CompletableFuture<Health> 진행중;

    private record 인증실패(Instant 시각, Throwable 오류) {
    }

    /**
     * <b>검색이 아니라 연결/바인드만 확인한다.</b> 엔트리를 훑으면 디렉터리 크기에 따라
     * 프로브 비용이 커지고, 헬스체크가 LDAP 서버에 부담을 주는 본말전도가 된다.
     * {@code getReadOnlyContext()} 는 바인드까지 수행하므로 자격증명 문제도 함께 잡힌다.
     * 다만 인증이 실패하면 {@link #인증_실패_뒤_쉼} 동안은 바인드를 쉬고 마지막 실패로 DOWN 을 답한다 — 틀린 비밀번호를 프로브가 되풀이하면 서비스 계정이 잠긴다.
     *
     * <p><b>주소 목록 하나로 바인드 하나만 한다</b>(설계 2026-11-14 F5). JNDI 가 목록을 앞에서부터 시도하므로 주소마다 따로 바인드할 필요가 없고, 따로 하면 틀린
     * 비밀번호가 DC 수만큼 쌓여 AD 가 하나의 잠금 횟수로 센다. 앞 주소가 접속만 받고 답하지 않으면 {@link #PROBE_TIMEOUT} 안에 DOWN 이다. 어느 주소에 붙었는지는
     * 세부 {@code connectedUrl} 에 싣는다(JNDI 가 덮어쓴 값을 읽을 수 있을 때만).
     *
     * <p><b>프로브 상한 뒤에 온 인증 실패도 기억하고, 진행 중인 바인드가 있으면 새로 바인드하지 않는다</b> — 앞 주소가 답하지 않는 동안 틀린 비밀번호가 프로브마다
     * 다음 DC 에 쌓이지 않게. 바인드는 {@link CompletableFuture} 하나로 따로 돌고 결과(인증 실패의 시각·오류, 성공이면 붙은 주소)를 스스로 적는다. 프로브는 그것을
     * {@code Mono.fromFuture(바인드, true)} 로 기다린다 — 둘째 인자(suppressCancel)가 상한이 끝나 구독을 끊어도 바인드를 취소하지 않게 한다. 바인드 작업은
     * 스케줄러에 넘긴 뒤 아무도 손잡이를 쥐지 않아 끊기거나 인터럽트되지 않는다. 그 사이에 온 프로브는 같은 바인드를 기다린다.
     *
     * <p>쉼과 진행 중인 바인드는 구독할 때 본다 — {@code health()} 를 부른 때가 아니다.
     *
     * <p>LDAP 은 블로킹이라 {@code boundedElastic} 으로 격리한다 — 이벤트 루프에서
     * 직접 부르면 프로브 하나가 애플리케이션 전체를 멈춘다.
     */
    @Override
    public Mono<Health> health() {
        return Mono.defer(() -> {
            CompletableFuture<Health> 바인드;
            boolean 새로_시작한다 = false;
            synchronized (자물쇠) {
                인증실패 기억 = 마지막_인증실패;
                if (기억 != null && clock.instant().isBefore(기억.시각().plus(인증_실패_뒤_쉼))) {
                    return Mono.just(쉬는_동안의_답(기억));
                }
                if (진행중 == null) {
                    진행중 = new CompletableFuture<>();
                    새로_시작한다 = true;
                }
                바인드 = 진행중;
            }
            if (새로_시작한다) {
                시작한다(바인드);
            }
            // suppressCancel — 상한이 구독을 끊어도 바인드는 취소하지 않는다. 늦게 온 결과는 바인드 작업이 이미 적는다
            return Mono.fromFuture(바인드, true)
                    .timeout(PROBE_TIMEOUT)
                    .onErrorResume(error -> Mono.just(Health.down(error).build()));
        });
    }

    /** 바인드 작업을 {@code boundedElastic} 에 넘긴다. 돌려받은 {@code Disposable} 은 쥐지 않는다 — 쥐면 누군가 그것으로 바인드를 끊을 수 있다. */
    private void 시작한다(CompletableFuture<Health> 바인드) {
        try {
            Schedulers.boundedElastic().schedule(() -> 바인드한다(바인드));
        } catch (RuntimeException e) {
            // 스케줄러가 받지 않으면(대기열이 참) 자리를 비운다 — 비우지 않으면 다음 프로브들이 시작하지도 않은 바인드를 기다린다
            자리를_비운다();
            바인드.completeExceptionally(e);
        }
    }

    /**
     * 바인드 하나를 끝까지 하고 결과를 스스로 적는다 — 기다리는 프로브가 상한으로 먼저 끝났어도 여기서 적은 인증 실패가 쉼을 건다. 결과를 적으며 자리를 비운 뒤에
     * 답을 넘긴다 — 답을 받은 프로브의 다음 구독이 쉼을 보거나 새로 바인드할 수 있게.
     */
    private void 바인드한다(CompletableFuture<Health> 바인드) {
        try {
            Optional<String> 붙은 = 붙는다();
            synchronized (자물쇠) {
                마지막_인증실패 = null;
                진행중 = null;
            }
            바인드.complete(붙은_답(붙은));
        } catch (org.springframework.ldap.AuthenticationException e) {
            인증실패 기억 = new 인증실패(clock.instant(), e);
            synchronized (자물쇠) {
                마지막_인증실패 = 기억;
                진행중 = null;
            }
            바인드.complete(쉬는_동안의_답(기억));
        } catch (Throwable e) {
            // 인증이 아닌 실패는 쉬지 않는다 — 다음 프로브가 다시 바인드한다
            자리를_비운다();
            바인드.completeExceptionally(e);
        }
    }

    /** 바인드하고 붙은 주소를 읽는다. */
    private Optional<String> 붙는다() throws NamingException {
        DirContext ctx = contextSource.getReadOnlyContext();
        try {
            return LdapUrls.붙은_주소(ctx);
        } finally {
            ctx.close();
        }
    }

    private void 자리를_비운다() {
        synchronized (자물쇠) {
            진행중 = null;
        }
    }

    private Health 붙은_답(Optional<String> 붙은) {
        Health.Builder up = Health.up().withDetail("urls", properties.getUrls());
        붙은.ifPresent(주소 -> up.withDetail("connectedUrl", 주소));
        return up.withDetail("baseDn", properties.getBaseDn())
                .withDetail("strategy", properties.getStrategy())
                .build();
    }

    private static Health 쉬는_동안의_답(인증실패 기억) {
        return Health.down(기억.오류())
                .withDetail("다음 확인", 기억.시각().plus(인증_실패_뒤_쉼).toString())
                .withDetail("이유", "인증 실패 뒤 서비스 계정 잠금을 막으려고 바인드를 쉰다")
                .build();
    }
}

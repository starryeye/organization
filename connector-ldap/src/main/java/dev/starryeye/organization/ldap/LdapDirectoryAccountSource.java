package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.port.DirectoryAccountSource;
import dev.starryeye.organization.core.port.DirectoryReadException;
import dev.starryeye.organization.core.port.DirectoryUnavailableException;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.CommunicationException;
import org.springframework.ldap.ServiceUnavailableException;
import org.springframework.ldap.core.LdapTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 한 사람 즉시 회수가 쓰는 LDAP 읽기(설계 2026-11-28 V2·§5.2 "주소 넘김과 상한").
 *
 * <p><b>주소를 차례로 한 번씩.</b> {@code 시작_주소별_템플릿} 을 앞에서부터 한 번씩만 쓴다. 대기(backoff)는 없다. 일시 장애의 표준 신호 —
 * Spring LDAP {@link CommunicationException}·{@link ServiceUnavailableException}·{@link TimeoutException}(주소 상한 초과) — 일 때만 다음
 * 템플릿으로 넘기고, 그 밖의 실패(인증 거절·검색 베이스 없음·데이터 오류, 모르는 종류까지)는 바로 {@link DirectoryReadException} 이다.
 * 다 쓰면 {@link DirectoryUnavailableException} 이다.
 *
 * <p><b>상한 둘.</b> 주소 하나는 10초, 요청 전체는 30초다(코드 상수). 시도마다 {@code timeout(주소당)} 을, 사슬 전체에 {@code timeout(전체)} 를
 * 건다 — 바깥이 남은 시간에서 끊으므로 "min(남은 상한, 주소당)" 과 같다. 전체 상한 초과도 {@link DirectoryUnavailableException} 이다.
 *
 * <p><b>JNDI 의 막힌 호출은 끊기지 않는다.</b> Reactor 의 {@code timeout} 은 구독을 끊을 뿐 블로킹 호출을 중단하지 못해, 그 호출은
 * {@code ldap.read-timeout} 까지 {@code boundedElastic} 스레드에 남는다(§5.2). 같은 이유로 {@code ldap.read-timeout} 이 주소 상한보다 짧으면
 * 그 시간 초과는 JNDI 가 먼저 내므로 일시 장애 신호가 아니라 500 쪽이다.
 *
 * <p>예외 메시지·로그에 직원 id·DN 을 싣지 않는다. WARN 은 설정 값인 시작 주소·시도한 주소 수·원인의 클래스 이름만 적는다.
 */
@Slf4j
public class LdapDirectoryAccountSource implements DirectoryAccountSource {

    static final Duration 전체_상한 = Duration.ofSeconds(30);
    static final Duration 주소당_상한 = Duration.ofSeconds(10);

    private final List<LdapTemplate> 템플릿들;
    private final LdapMappingStrategy strategy;
    private final Duration 전체;
    private final Duration 주소당;

    public LdapDirectoryAccountSource(List<LdapTemplate> 시작_주소별_템플릿, LdapMappingStrategy strategy) {
        this(시작_주소별_템플릿, strategy, 전체_상한, 주소당_상한);
    }

    LdapDirectoryAccountSource(List<LdapTemplate> 시작_주소별_템플릿, LdapMappingStrategy strategy,
            Duration 전체_상한, Duration 주소당_상한) {
        if (시작_주소별_템플릿.isEmpty()) {
            throw new IllegalArgumentException("시작 주소별 템플릿이 비었다");
        }
        this.템플릿들 = Collections.unmodifiableList(new ArrayList<>(시작_주소별_템플릿));
        this.strategy = strategy;
        this.전체 = 전체_상한;
        this.주소당 = 주소당_상한;
    }

    @Override
    public Mono<AccountState> accountState(String employeeId, String externalId) {
        AtomicInteger 시도한_주소 = new AtomicInteger();
        return 시도한다(0, employeeId, externalId, 시도한_주소)
                .timeout(전체)
                .onErrorMap(TimeoutException.class, e -> {
                    log.warn("한 사람 읽기가 일시 장애로 끝났다 — 시도한 주소 {}개, 마지막 원인 {}",
                            시도한_주소.get(), e.getClass().getSimpleName());
                    return new DirectoryUnavailableException("디렉터리 읽기가 상한 안에 끝나지 않았습니다", e);
                });
    }

    private Mono<AccountState> 시도한다(int k, String employeeId, String externalId, AtomicInteger 시도한_주소) {
        return Mono.fromCallable(() -> {
                    시도한_주소.incrementAndGet();
                    return strategy.readAccount(템플릿들.get(k), employeeId, externalId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(주소당)
                .onErrorResume(e -> {
                    if (!일시_장애인가(e)) {
                        return Mono.error(new DirectoryReadException(
                                "디렉터리에서 직원을 읽지 못했습니다(" + e.getClass().getSimpleName() + ")", e));
                    }
                    if (k + 1 >= 템플릿들.size()) {
                        log.warn("한 사람 읽기가 일시 장애로 끝났다 — 시도한 주소 {}개, 마지막 원인 {}",
                                k + 1, e.getClass().getSimpleName());
                        return Mono.error(new DirectoryUnavailableException(
                                "디렉터리의 모든 주소가 일시 장애로 답하지 않았습니다", e));
                    }
                    log.warn("한 사람 읽기를 다음 주소로 넘긴다 — 실패한 시작 주소 {} ({}), 다음 시작 주소 {}",
                            LdapUrls.첫_주소(템플릿들.get(k).getContextSource()).orElse("?"),
                            e.getClass().getSimpleName(),
                            LdapUrls.첫_주소(템플릿들.get(k + 1).getContextSource()).orElse("?"));
                    return 시도한다(k + 1, employeeId, externalId, 시도한_주소);
                });
    }

    private static boolean 일시_장애인가(Throwable e) {
        return e instanceof CommunicationException
                || e instanceof ServiceUnavailableException
                || e instanceof TimeoutException;
    }
}

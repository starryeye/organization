package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.DailyJobClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 하루 한 번 작업을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1, 2026-10-27 §9). 인스턴스마다 같은 cron 이 돌므로, 그날 표지를 잡은 인스턴스만
 * 일을 한다. 날짜는 앱 시계의 UTC 날짜다.
 *
 * <p><b>표지는 일이 성공해야 DONE 이다.</b> 예전에는 잡기만 하고 끝내지 않아, 아래 세 경우 모두 그날의 표지를 써 버리고 일은 다음 날까지 돌지 않았다. 이제는 셋
 * 다 같은 날 cron 창(매시 시도) 안의 다음 시도가 다시 잡는다.
 * <ul>
 *   <li>일이 실패한 경우(오류·빈 끝·취소) — 표지를 푼다.</li>
 *   <li>값이 성공이 아닌 경우 — 성공 판정은 부르는 쪽이 넘긴다. 아카이빙은 SUCCEEDED 만 성공이라 FAILED·ABORTED(재적재 중)면 표지를 푼다.</li>
 *   <li>잡은 인스턴스가 죽은 경우 — 풀 주체가 없으므로 임대가 끝나면 다시 잡힌다.</li>
 * </ul>
 *
 * <p><b>임대.</b> 잡은 때 + {@code jobTimeout} + {@link #임대_여유} 다. 아카이빙은 기한({@code jobTimeout})에 끝나므로, 살아 있는 인스턴스가 쥔 표지를
 * 남이 다시 잡지 않는다. 정리에는 기한이 없어 임대를 넘길 수 있다. 잡을 때마다 owner 를 새로 만들어, 완료·해제는 그 owner 의 표지에만 닿는다 — 임대를 잃은
 * 인스턴스의 늦은 해제가 새로 잡은 표지를 지우지 않는다. 그래도 둘이 함께 돌면 정확성은 깨지지 않고 비용만 든다(정리는 멱등, 아카이빙은 스냅샷 하나와
 * BatchCheck 를 더 쓴다).
 */
@Slf4j
@RequiredArgsConstructor
public class DailyOnce {

    /** 임대 = 잡은 때 + jobTimeout + 이 여유. 기한으로 끝난 일이 표지를 끝낼 틈이다. */
    static final Duration 임대_여유 = Duration.ofMinutes(5);

    private final DailyJobClaims claims;
    private final Clock clock;
    private final Duration jobTimeout;

    /**
     * 오늘 표지를 잡은 인스턴스만 {@code work} 를 돌린다. 못 잡으면 빈 Mono — 다른 인스턴스가 이미 했거나 하고 있다.
     *
     * <p>값이 {@code 성공} 을 통과하면 표지를 DONE 으로, 통과하지 못한 값·오류·빈 끝·취소면 표지를 푼다. 끝낼 때는 잡은 날짜를 그대로 쓴다 — 자정을 넘겨 끝나도
     * 다시 계산하지 않는다. 끝내기의 오류는 WARN 하고 삼킨다(풀지 못한 표지는 임대가 끝나면 다시 잡힌다). 일의 값·오류는 그대로 낸다.
     */
    public <T> Mono<T> run(String job, Mono<T> work, Predicate<? super T> 성공) {
        return Mono.defer(() -> {
            Instant 지금 = clock.instant();
            LocalDate 오늘 = LocalDate.ofInstant(지금, ZoneOffset.UTC);
            String owner = UUID.randomUUID().toString();
            Instant 임대 = 지금.plus(jobTimeout).plus(임대_여유);
            return claims.claim(job, 오늘, owner, 임대).flatMap(잡았다 -> {
                if (!잡았다) {
                    log.info("오늘({}) {} 은 다른 인스턴스가 이미 했거나 하고 있다 — 건너뛴다", 오늘, job);
                    return Mono.<T>empty();
                }
                return 쥐고_돈다(new 쥔_표지(job, 오늘, owner), work, 성공);
            });
        });
    }

    /**
     * 끝을 한 번만 정한다 — 값·오류·빈 끝 가운데 먼저 온 것이 정하고, 그 뒤의 취소(값을 받은 뒤 위쪽을 끊는 구독자도 있다)는 표지를 다시 건드리지 않는다.
     * 취소일 때의 해제는 따로 구독하고 기다리지 않는다 — 취소된 쪽에는 기다릴 구독자가 없다.
     */
    private <T> Mono<T> 쥐고_돈다(쥔_표지 표지, Mono<T> work, Predicate<? super T> 성공) {
        AtomicBoolean 끝을_정했다 = new AtomicBoolean();
        return work
                .flatMap(value -> {
                    끝을_정했다.set(true);
                    Mono<Void> 끝내기 = 성공.test(value) ? 완료한다(표지) : 푼다(표지, "성공이 아니다");
                    return 끝내기.thenReturn(value);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    끝을_정했다.set(true);
                    return 푼다(표지, "결과 없이 끝났다").then(Mono.<T>empty());
                }))
                .onErrorResume(error -> {
                    끝을_정했다.set(true);
                    return 푼다(표지, "실패했다").then(Mono.<T>error(error));
                })
                .doOnCancel(() -> {
                    if (끝을_정했다.compareAndSet(false, true)) {
                        푼다(표지, "취소됐다").subscribe();
                    }
                });
    }

    private Mono<Void> 완료한다(쥔_표지 표지) {
        return Mono.defer(() -> claims.complete(표지.job(), 표지.day(), 표지.owner()))
                .onErrorResume(error -> {
                    log.warn("{}({}) 표지를 완료로 적지 못했다 — 임대가 끝나면 같은 날 다시 돌 수 있다", 표지.job(), 표지.day(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> 푼다(쥔_표지 표지, String 까닭) {
        return Mono.defer(() -> {
                    log.info("{}({}) 이 {} — 표지를 풀어 창 안의 다음 시도가 다시 잡는다", 표지.job(), 표지.day(), 까닭);
                    return claims.release(표지.job(), 표지.day(), 표지.owner());
                })
                .onErrorResume(error -> {
                    log.warn("{}({}) 표지를 풀지 못했다 — 임대가 끝난 뒤의 시도가 다시 잡는다", 표지.job(), 표지.day(), error);
                    return Mono.empty();
                });
    }

    /** 이번에 잡은 표지 — 끝낼 때도 잡은 날짜와 owner 를 그대로 쓴다. */
    private record 쥔_표지(String job, LocalDate day, String owner) {
    }
}

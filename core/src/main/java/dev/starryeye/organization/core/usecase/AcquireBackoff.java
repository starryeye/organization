package dev.starryeye.organization.core.usecase;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * 다른 서버가 쥔 전역 락을 다시 시도하는 간격(설계 2026-10-07 §3.2) — AWS 권고의 지수 백오프 + full jitter. n번째(0부터) 재시도는
 * {@code 0 ~ min(최대_간격, 처음_간격 × 2ⁿ)} 사이 무작위로 기다린다. 경합이 짧으면 곧 다시 시도하고, 길면 많아야 100ms 간격으로 두드린다.
 *
 * <p><b>다음 시도 전에 남은 한도를 본다.</b> 걸린 시간에 이번 대기를 더해 한도에 닿으면 시도하지 않고 멈춘다. 날아가는 중인 시도는 끊지 않는다 —
 * 서버에서 성공한 획득의 응답을 끊으면 그 락은 TTL(30초) 동안 새어 모든 쓰기를 막는다.
 *
 * <p><b>쥔 쪽이 재적재·동기화면 다시 시도하지 않는다</b>(§3.3) — 몇 분 걸리는 일이라 한도 안의 재시도가 결과를 바꾸지 못한다.
 */
final class AcquireBackoff {

    /** 첫 재시도 대기의 상한. */
    static final Duration 처음_간격 = Duration.ofMillis(10);
    /** 대기 상한이 여기서 멈춘다 — 경합이 이어져도 서버 하나가 초당 약 15~20번만 두드린다(추정). */
    static final Duration 최대_간격 = Duration.ofMillis(100);

    private final DoubleSupplier 무작위;

    AcquireBackoff(DoubleSupplier 무작위) {
        this.무작위 = 무작위;
    }

    static AcquireBackoff 무작위로() {
        return new AcquireBackoff(() -> ThreadLocalRandom.current().nextDouble());
    }

    /** n번째(0부터) 재시도 전 대기. */
    Duration 대기(long n) {
        long 상한 = Math.min(최대_간격.toMillis(), 처음_간격.toMillis() << Math.min(n, 10));
        return Duration.ofMillis((long) (상한 * 무작위.getAsDouble()));
    }

    /**
     * {@code 시도} 를 다시 구독하며 잡는다 — 같은 {@code Mono} 를 다시 구독하므로 재시도도 같은 토큰이다(점검 S14). 다시 시도하는 실패는
     * 쥔 쪽이 재적재·동기화가 아닌 {@link LockUnavailableException} 뿐이다. 그 밖의 실패는 그대로 흘린다. 한도에 닿으면 마지막 실패의
     * 대기를 물려받은 503 이다.
     */
    <T> Mono<T> 잡는다(Mono<T> 시도, Duration 한도) {
        return Mono.defer(() -> {
            long 시작 = 지금();
            return 시도.retryWhen(Retry.from(신호들 -> 신호들.concatMap(신호 -> {
                Throwable 실패 = 신호.failure();
                if (!(실패 instanceof LockUnavailableException 락) || 락.긴_작업이_쥐었다()) {
                    return Mono.<Long>error(실패);
                }
                Duration 대기 = 대기(신호.totalRetries());
                if (지금() - 시작 + 대기.toMillis() >= 한도.toMillis()) {
                    return Mono.<Long>error(new LockUnavailableException("변경 락을 얻지 못했습니다", 락.retryAfter()));
                }
                return Mono.delay(대기);
            })));
        });
    }

    /** 스케줄러 시계(밀리초) — 가상 시간 테스트에서도 한도와 대기가 같은 시계를 본다. */
    static long 지금() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }
}

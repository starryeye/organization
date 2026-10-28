package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.TemporaryFailureException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 다른 서버가 쥔 전역 락을 다시 시도하는 간격(설계 2026-10-07 §3.2) — AWS 권고의 지수 백오프 + full jitter. n번째(0부터) 재시도는
 * {@code 0 ~ min(최대_간격, 처음_간격 × 2ⁿ)} 사이 무작위로 기다린다. 경합이 짧으면 곧 다시 시도하고, 길면 많아야 100ms 간격으로 두드린다.
 *
 * <p><b>시도마다 한도를 준다</b>(설계 2026-10-27 §6). 값은 {@link #시도_한도} — {@code min(남은 한도, 1초)} 이고 하한은 200ms 다. 락을 잡는 PutItem 은 보통
 * 수십 ms 라, 스로틀로 매달린 시도 하나가 남은 한도를 다 쓰지 않고 끊겨 두세 번 다시 시도할 여지를 남긴다. 끊는 것은 이 한도를 받은 저장소다 — 백오프는
 * 날아가는 중인 시도를 스스로 끊지 않는다.
 *
 * <p><b>시도의 결과는 셋으로 가른다.</b>
 * <ul>
 *   <li>조건 실패({@link LockUnavailableException}) — 남이 쥐었다. 쉬었다 다시 시도한다. 걸린 시간에 이번 대기를 더해 한도에 닿으면 시도하지 않고 멈춘다.</li>
 *   <li>시도 한도로 끊김({@link LockAttemptTimeoutException}) — 결과를 모른다. 서버에서 성공했을 수 있다. 기다리지 않고 같은 토큰으로 다시 시도한다 —
 *       같은 토큰의 다시 시도는 성공한 획득을 그대로 다시 얻는다. 걸린 시간이 한도에 닿았으면 기본 대기(10초)의 503 으로 멈춘다.</li>
 *   <li>그 밖의 오류(SDK 재시도를 다 쓴 스로틀·5xx·네트워크) — 곧바로 그대로 흘린다.</li>
 * </ul>
 * 결과를 모르는 시도(끊김·그 밖의 오류)를 남긴 채 멈추면 부르는 쪽이 그 토큰으로 반납을 한 번 보낸다 — 서버에서 성공한 획득이 TTL(30초) 동안 새어
 * 모든 쓰기를 막지 않게 한다({@code IncrementalSyncUseCase}). 그 반납도 실패하면 TTL 이 푼다.
 *
 * <p><b>쥔 쪽이 재적재·동기화면 다시 시도하지 않는다</b>(§3.3) — 몇 분 걸리는 일이라 한도 안의 재시도가 결과를 바꾸지 못한다.
 */
final class AcquireBackoff {

    /** 첫 재시도 대기의 상한. */
    static final Duration 처음_간격 = Duration.ofMillis(10);
    /** 대기 상한이 여기서 멈춘다 — 경합이 이어져도 서버 하나가 초당 약 15~20번만 두드린다(추정). */
    static final Duration 최대_간격 = Duration.ofMillis(100);
    /** 시도 한도의 상한 — 매달린 시도가 와도 3초 한도 안에서 두세 번 다시 시도할 수 있다. */
    static final Duration 시도_상한 = Duration.ofSeconds(1);
    /** 시도 한도의 하한 — 남은 한도가 거의 없어도 수십 ms 인 PutItem 이 끝날 시간은 준다. */
    static final Duration 시도_하한 = Duration.ofMillis(200);

    private final DoubleSupplier 무작위;

    AcquireBackoff(DoubleSupplier 무작위) {
        this.무작위 = 무작위;
    }

    static AcquireBackoff 무작위로() {
        return new AcquireBackoff(() -> ThreadLocalRandom.current().nextDouble());
    }

    /** 남은 한도로 정하는 시도 하나의 한도 — {@code max(200ms, min(남은, 1초))}. */
    static Duration 시도_한도(Duration 남은) {
        Duration 작은_쪽 = 남은.compareTo(시도_상한) < 0 ? 남은 : 시도_상한;
        return 작은_쪽.compareTo(시도_하한) > 0 ? 작은_쪽 : 시도_하한;
    }

    /** n번째(0부터) 재시도 전 대기. */
    Duration 대기(long n) {
        long 상한 = Math.min(최대_간격.toMillis(), 처음_간격.toMillis() << Math.min(n, 10));
        return Duration.ofMillis((long) (상한 * 무작위.getAsDouble()));
    }

    /**
     * {@code 시도} 에 시도 한도를 주며 잡는다 — 다시 구독할 때마다 남은 한도로 {@link #시도_한도} 를 새로 계산한다. 토큰은 부르는 쪽이 정해 {@code 시도} 에
     * 담는다(같은 토큰, 점검 S14). 다시 시도하는 실패는 시도 한도로 끊긴 {@link LockAttemptTimeoutException} 과, 쥔 쪽이 재적재·동기화가 아닌
     * {@link LockUnavailableException} 이다. 그 밖의 실패는 그대로 흘린다. 조건 실패로 한도에 닿으면 마지막 실패의 대기를 물려받은 503 이고,
     * 끊긴 시도로 한도에 닿으면 기본 대기의 503 이다.
     */
    <T> Mono<T> 잡는다(Function<Duration, Mono<T>> 시도, Duration 한도) {
        return Mono.defer(() -> {
            long 시작 = 지금();
            Mono<T> 한_번 = Mono.defer(() -> 시도.apply(시도_한도(한도.minusMillis(지금() - 시작))));
            return 한_번.retryWhen(Retry.from(신호들 -> 신호들.concatMap(신호 -> {
                Throwable 실패 = 신호.failure();
                if (실패 instanceof LockAttemptTimeoutException) {
                    if (지금() - 시작 >= 한도.toMillis()) {
                        return Mono.<Long>error(new LockUnavailableException("변경 락을 얻지 못했습니다", TemporaryFailureException.기본_대기));
                    }
                    // 결과를 모른다 — 기다리지 않는다. 같은 토큰이라 서버에서 성공한 획득이면 그대로 다시 얻는다
                    return Mono.just(신호.totalRetries());
                }
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

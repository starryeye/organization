package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 락 리스를 쥔 채 일을 돌린다(설계 2026-10-02 §3.1). 도는 동안 {@code renewInterval} 마다 리스를 갱신한다.
 *
 * <p><b>잃었다고 보는 것은 둘뿐이다(설계 2026-10-13 §3.1).</b>
 * <ul>
 *   <li>갱신이 {@link LockUnavailableException} 으로 끝났다 — 저장소의 토큰 조건이 깨졌다, 곧 남이 가져갔다. 곧바로 멈춘다.</li>
 *   <li>마지막으로 갱신에 성공한 때(처음에는 일을 시작한 때)부터 갱신 주기 두 번 안에 성공하지 못했다 — TTL(30초)보다 먼저 멈춘다.</li>
 * </ul>
 * 그 밖의 실패(스로틀·네트워크·SDK 오류)와 한 번 시도의 시간 초과는 잃었다고 단정하지 않는다 — 그 한도 안에서 쉬며 다시 시도한다. 한 번 시도는
 * 주기의 절반 안에 답해야 하고(매달린 연결 하나가 한도를 다 쓰지 않게), 쉬는 시간은 주기의 1/50 에서 두 배씩 늘려 주기의 1/5 까지다(기본 10초면
 * 5초, 200ms~2초). 한도는 전과 같으므로 TTL 전에 멈춘다는 안전 여유는 줄지 않는다.
 *
 * <p>{@link SyncJobs}(동기화·재적재)와 {@link IncrementalSyncUseCase}(SCIM 쓰기)가 함께 쓴다. 대부분의 SCIM 쓰기는 갱신 주기 안에 끝나
 * 갱신이 한 번도 일어나지 않는다. 시간은 Reactor 스케줄러 시계로 잰다 — 가상 시간 테스트가 그대로 된다.
 */
@Slf4j
@RequiredArgsConstructor
final class LeaseKeeper {

    private final MutationLock lock;
    private final Duration renewInterval;
    private final LockObserver lockObserver;

    /**
     * {@code work} 를 돌리며 리스를 갱신한다. 리스를 잃으면 {@code work} 를 취소하고 {@link LockUnavailableException} 으로 끝난다.
     * {@code work} 가 어떻게 끝나든 갱신도 멈춘다.
     *
     * @param 상실_사유 리스를 잃었을 때 {@link LockObserver#leaseLost} 와 로그에 남길 말
     */
    <T> Mono<T> keep(LockLease lease, Mono<T> work, String 상실_사유) {
        return Mono.defer(() -> {
            Sinks.One<T> 상실 = Sinks.one();
            AtomicLong 마지막_성공 = new AtomicLong(지금());
            Disposable heartbeat = Flux.interval(renewInterval, renewInterval)
                    // 갱신(다시 시도 포함)이 이어지는 동안 온 tick 은 버린다 — 한도는 마지막 성공에서 재므로 tick 을 놓쳐도 늦어지지 않는다
                    .onBackpressureDrop()
                    .concatMap(tick -> 갱신한다(lease, 마지막_성공, 상실_사유))
                    .subscribe(renewed -> {
                    }, error -> {
                        log.error("{} — 락 리스를 갱신하지 못했다. 하던 일을 멈춘다", 상실_사유, error);
                        lockObserver.leaseLost(상실_사유);
                        상실.tryEmitError(new LockUnavailableException("작업 도중 락 리스를 잃었습니다", error));
                    });
            return Mono.firstWithSignal(work, 상실.asMono())
                    .doFinally(signal -> heartbeat.dispose());
        });
    }

    /** 한 tick 의 갱신. 조건 실패면 곧바로 오류다. 그 밖의 실패는 마지막 성공 + 2×주기 까지 쉬며 다시 시도한다. */
    private Mono<LockLease> 갱신한다(LockLease lease, AtomicLong 마지막_성공, String 상실_사유) {
        return Mono.defer(() -> {
            long 남은_밀리초 = 마지막_성공.get() + 2 * renewInterval.toMillis() - 지금();
            if (남은_밀리초 <= 0) {
                return Mono.error(new TimeoutException("마지막 갱신 성공에서 갱신 주기 두 번이 지났다"));
            }
            return Mono.defer(() -> lock.renew(lease))
                    .timeout(renewInterval.dividedBy(2))
                    .retryWhen(Retry.backoff(Long.MAX_VALUE, renewInterval.dividedBy(50))
                            .maxBackoff(renewInterval.dividedBy(5))
                            .jitter(0)
                            // 남이 가져간 것은 다시 시도하지 않는다 — 그대로 아래로 흘러 곧바로 멈춘다
                            .filter(error -> !(error instanceof LockUnavailableException))
                            .doBeforeRetry(signal -> log.warn("{} — 락 리스 갱신이 실패해 다시 시도한다({}번째 실패): {}",
                                    상실_사유, signal.totalRetries() + 1, signal.failure().toString())))
                    .timeout(Duration.ofMillis(남은_밀리초))
                    .doOnNext(renewed -> 마지막_성공.set(지금()));
        });
    }

    private static long 지금() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }
}

package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * 락 리스를 쥔 채 일을 돌린다(설계 2026-10-02 §3.1). 도는 동안 {@code renewInterval} 마다 리스를 갱신하고, 갱신이 실패하면 — 남이 가져갔거나
 * 저장소가 답하지 않으면 — 그 일을 멈춘다. 갱신이 주기 안에 답하지 않아도 잃은 것으로 본다 — 마지막 성공 + 2×주기(20초) 안에 멈춰 TTL(30초)보다
 * 먼저다. 리스 TTL 보다 오래 걸리는 일도 끝까지 가고, 리스를 잃은 뒤에는 더 쓰지 않는다.
 *
 * <p>{@link SyncJobs}(동기화·재적재)와 {@link IncrementalSyncUseCase}(SCIM 쓰기)가 함께 쓴다. 대부분의 SCIM 쓰기는 갱신 주기 안에 끝나
 * 갱신이 한 번도 일어나지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
final class LeaseKeeper {

    private final MutationLock lock;
    private final Duration renewInterval;
    private final LockObserver lockObserver;

    /**
     * {@code work} 를 돌리며 리스를 갱신한다. 갱신이 실패하거나 {@code renewInterval} 안에 답하지 않으면 {@code work} 를 취소하고
     * {@link LockUnavailableException} 으로 끝난다.
     * {@code work} 가 어떻게 끝나든 갱신도 멈춘다.
     *
     * @param 상실_사유 리스를 잃었을 때 {@link LockObserver#leaseLost} 와 로그에 남길 말
     */
    <T> Mono<T> keep(LockLease lease, Mono<T> work, String 상실_사유) {
        return Mono.defer(() -> {
            Sinks.One<T> 상실 = Sinks.one();
            Disposable heartbeat = Flux.interval(renewInterval, renewInterval)
                    .concatMap(tick -> lock.renew(lease).timeout(renewInterval))
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
}

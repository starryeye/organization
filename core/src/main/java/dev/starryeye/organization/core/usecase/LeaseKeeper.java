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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 락 리스를 쥔 채 일을 돌린다(설계 2026-10-02 §3.1). 도는 동안 {@code renewInterval} 마다 리스를 갱신한다.
 *
 * <p><b>잃었다고 보는 것은 둘뿐이다(설계 2026-10-13 §3.1).</b>
 * <ul>
 *   <li>갱신이 {@link LockUnavailableException} 으로 끝났다 — 저장소의 토큰 조건이 깨졌다, 곧 남이 가져갔다. 곧바로 멈춘다.</li>
 *   <li>마지막으로 성공한 갱신을 보낸 때(처음에는 일을 시작한 때)부터 갱신 주기 두 번 안에 성공하지 못했다 — TTL(30초)보다 먼저 멈춘다.
 *       저장소가 만료 시각을 요청을 보낸 때 + TTL 로 정하므로 답이 온 때가 아니라 보낸 때로 잰다 — 답이 온 때로 재면 느린 성공 하나가 TTL 전 여유를 줄인다.</li>
 * </ul>
 * 그 밖의 실패(스로틀·네트워크·SDK 오류)와 한 번 시도의 시간 초과는 잃었다고 단정하지 않는다 — 그 한도 안에서 쉬며 다시 시도한다. 한 번 시도는
 * 주기의 절반 안에 답해야 하고(매달린 연결 하나가 한도를 다 쓰지 않게), 쉬는 시간은 주기의 1/50 에서 두 배씩 늘려 주기의 1/5 까지다(기본 10초면
 * 5초, 200ms~2초). 한도는 전과 같으므로 TTL 전에 멈춘다는 안전 여유는 줄지 않는다.
 *
 * <p>{@link SyncJobs}(동기화·재적재)와 {@link IncrementalSyncUseCase}(SCIM 쓰기)가 함께 쓴다. 대부분의 SCIM 쓰기는 갱신 주기 안에 끝나
 * 갱신이 한 번도 일어나지 않는다.
 *
 * <p><b>한도는 시각을 빼서 재지 않고 타이머로 잰다.</b> 갱신을 보낼 때마다 "주기 두 번 뒤에 닿는 마감"({@link 마감}) 을 걸고, 그 갱신이 성공하면 그
 * 마감이 새 한도가 된다. 스케줄러의 지연은 단조 시계라 NTP 가 벽시계를 옮겨도 한도가 늘거나(TTL 을 넘겨서도 일한다) 줄지(잃지 않았는데 멈춘다)
 * 않는다. 벽시계({@code now})는 로그에 남은 시간을 보여 주는 데만 쓴다. 타이머는 Reactor 스케줄러에 걸므로 가상 시간 테스트가 그대로 된다.
 * 이 내성은 이 멈춤 한도에만 해당한다 — 락의 만료 시각은 저장소가 각 서버의 벽시계로 쓰고 판단한다(점검 S15, 서버 시계를 NTP 로 맞춘다는 운영 전제).
 */
@Slf4j
@RequiredArgsConstructor
final class LeaseKeeper {

    private final MutationLock lock;
    private final Duration renewInterval;
    private final LockObserver lockObserver;

    /** 한도를 넘겨 멈추는 두 길(마감이 이미 지난 채 시작한 갱신, 다시 시도하는 동안 마감에 닿은 갱신)이 함께 쓰는 말 */
    private static final String 한도_초과_사유 = "마지막 갱신 성공에서 갱신 주기 두 번이 지났다";

    /**
     * {@code work} 를 돌리며 리스를 갱신한다. 리스를 잃으면 {@code work} 를 취소하고 {@link LockUnavailableException} 으로 끝난다.
     * {@code work} 가 어떻게 끝나든 갱신도 멈춘다.
     *
     * @param 상실_사유 리스를 잃었을 때 {@link LockObserver#leaseLost} 와 로그에 남길 말
     */
    <T> Mono<T> keep(LockLease lease, Mono<T> work, String 상실_사유) {
        return Mono.defer(() -> {
            Sinks.One<T> 상실 = Sinks.one();
            // 처음 한도는 일을 시작한 때부터 잰다
            AtomicReference<마감> 지금_마감 = new AtomicReference<>(new 마감());
            Disposable heartbeat = Flux.interval(renewInterval, renewInterval)
                    // 갱신(다시 시도 포함)이 이어지는 동안 온 tick 은 버린다 — 한도는 마지막 성공한 갱신을 보낸 때에서 재므로 tick 을 놓쳐도 늦어지지 않는다
                    .onBackpressureDrop()
                    .concatMap(tick -> 갱신한다(lease, 지금_마감, 상실_사유))
                    .subscribe(renewed -> {
                    }, error -> {
                        log.error("{} — 락 리스를 갱신하지 못했다. 하던 일을 멈춘다", 상실_사유, error);
                        lockObserver.leaseLost(상실_사유);
                        상실.tryEmitError(new LockUnavailableException("작업 도중 락 리스를 잃었습니다", error));
                    });
            return Mono.firstWithSignal(work, 상실.asMono())
                    .doFinally(signal -> {
                        heartbeat.dispose();
                        지금_마감.get().푼다();
                    });
        });
    }

    /**
     * 한 tick 의 갱신. 조건 실패면 곧바로 오류다. 그 밖의 실패는 지금 한도(마지막으로 성공한 갱신을 보낸 때 + 2×주기)까지 쉬며 다시 시도한다.
     * 시도마다(다시 시도도 새 시도다) 보내기 직전에 새 마감을 걸고, 성공하면 그 마감이 한도가 된다.
     *
     * <p><b>한도는 두 timeout 을 모두 지난 성공만 옮긴다.</b> 시도가 주기의 절반에서 끊기는 순간 답이 오면, 끊긴 시도의 늦은 답이 아직 흘러올 수 있다.
     * 그 답이 한도를 옮기면 이 tick 이 지켜보는 한도({@code 한도}) 가 풀려, 일시 오류가 이어질 때 다시 시도가 끝없이 돈다. 받아들이지 않은 마감(실패·끊김·빈 답,
     * timeout 이 버린 늦은 답)은 그 시도가 끝날 때 푼다.
     */
    private Mono<LockLease> 갱신한다(LockLease lease, AtomicReference<마감> 지금_마감, String 상실_사유) {
        return Mono.defer(() -> {
            마감 한도 = 지금_마감.get();
            if (한도.지났다()) {
                return Mono.error(new TimeoutException(한도_초과_사유));
            }
            return Mono.defer(() -> {
                        Mono<LockLease> 갱신 = lock.renew(lease);
                        마감 보낸_때부터 = new 마감();
                        return 갱신.map(renewed -> 보낸_때부터)
                                .doFinally(signal -> {
                                    if (지금_마감.get() != 보낸_때부터) {
                                        보낸_때부터.푼다();
                                    }
                                });
                    })
                    .timeout(renewInterval.dividedBy(2))
                    .retryWhen(Retry.backoff(Long.MAX_VALUE, renewInterval.dividedBy(50))
                            .maxBackoff(renewInterval.dividedBy(5))
                            .jitter(0)
                            // 남이 가져간 것은 다시 시도하지 않는다 — 그대로 아래로 흘러 곧바로 멈춘다
                            .filter(error -> !(error instanceof LockUnavailableException))
                            .doBeforeRetry(signal -> log.warn("{} — 락 리스 갱신이 실패해 다시 시도한다({}번째 실패, 한도까지 {}ms): {}",
                                    상실_사유, signal.totalRetries() + 1, 한도.남은_밀리초(),
                                    signal.failure().toString())))
                    .timeout(한도.닿음(), Mono.error(new TimeoutException(한도_초과_사유)))
                    .doOnNext(새_한도 -> 지금_마감.getAndSet(새_한도).푼다())
                    .map(새_한도 -> lease);
        });
    }

    /**
     * 건 때부터 2×주기 뒤에 닿는 마감. 스케줄러 타이머로 재므로 벽시계가 움직여도 늘거나 줄지 않는다. 닿으면 {@link #닿음()} 이 값을 내고, 그 뒤에
     * 구독해도 곧바로 낸다. {@link #남은_밀리초()} 는 로그용이다 — 벽시계로 잰 어림값이고 판단에 쓰지 않는다.
     */
    private final class 마감 {

        private final Sinks.One<Boolean> 닿음 = Sinks.one();
        private final AtomicBoolean 지났다 = new AtomicBoolean();
        private final long 표시용_마감_밀리초;
        private final Disposable 타이머;

        마감() {
            long 한도_밀리초 = 2 * renewInterval.toMillis();
            this.표시용_마감_밀리초 = 벽시계() + 한도_밀리초;
            this.타이머 = Schedulers.parallel().schedule(() -> {
                지났다.set(true);
                닿음.tryEmitValue(true);
            }, 한도_밀리초, TimeUnit.MILLISECONDS);
        }

        boolean 지났다() {
            return 지났다.get();
        }

        Mono<Boolean> 닿음() {
            return 닿음.asMono();
        }

        long 남은_밀리초() {
            return 표시용_마감_밀리초 - 벽시계();
        }

        /**
         * 더 쓰지 않는 마감의 타이머를 거둔다 — 성공한 갱신이 새 마감을 걸었거나, 시도가 받아들여지지 않았거나, 일이 끝났다. 이미 닿았으면 거둘 것이
         * 없다 — 다른 스레드에서 도는 중인 타이머를 거두면 그 스레드를 인터럽트한다.
         */
        void 푼다() {
            if (!지났다.get()) {
                타이머.dispose();
            }
        }
    }

    private static long 벽시계() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }
}

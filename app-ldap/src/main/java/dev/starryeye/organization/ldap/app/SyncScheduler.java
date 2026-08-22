package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final FullSyncUseCase fullSync;
    private final TupleSnapshotRepository snapshots;
    private final SyncExecutionGuard executionGuard;
    private final SyncMetrics metrics;
    private final ObservationRegistry observations;

    @Scheduled(cron = "${sync.cron}")
    public void 전체동기화() {
        if (!executionGuard.tryAcquire()) {
            log.warn("이전 동기화가 아직 진행 중이라 이번 스케줄을 건너뛴다");
            return;
        }
        관측하며실행("sync.ldap.full",
                Mono.defer(() -> fullSync.execute(SyncTrigger.SCHEDULED))
                        .doOnNext(run -> {
                            metrics.record(run);
                            log.info("스케줄 동기화 완료: status={} written={} deleted={} failed={}",
                                    run.status(), run.writtenCount(), run.deletedCount(), run.failureCount());
                        })
                        .doOnError(error -> log.error("스케줄 동기화가 예기치 않게 실패했다", error))
                        .doFinally(signal -> executionGuard.release()));
    }

    /**
     * DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 명시적으로 정리한다.
     * 실제 AWS 에서는 TTL 이 처리하고 이 잡은 0건을 반환한다.
     */
    @Scheduled(cron = "${sync.purge-cron}")
    public void 만료스냅샷정리() {
        관측하며실행("sync.ldap.purge",
                Mono.defer(snapshots::purgeExpired)
                        .doOnNext(count -> log.info("만료 스냅샷 정리 완료: {}건", count))
                        .doOnError(error -> log.error("만료 스냅샷 정리에 실패했다", error)));
    }

    /**
     * 예약 작업에 traceId 를 붙인다.
     *
     * <p><b>왜 필요한가.</b> traceId 는 들어오는 HTTP 요청에서 시작된다. 예약 작업에는 그런
     * 요청이 없으므로 아무것도 하지 않으면 이 앱의 로그는 전부 traceId 가 빈칸이다 —
     * 하루 1회 동기화가 이 앱의 전부인데 정작 장애 때 볼 로그가 서로 묶이지 않는다.
     *
     * <p><b>왜 {@code subscribe} 만으로는 안 되는가.</b> {@code @Scheduled} 메서드는 void 라
     * 구독만 걸고 즉시 반환한다. 관측 스코프는 그때 닫히는데 실제 작업은 그 뒤에 다른
     * 스레드에서 돈다. 그래서 관측을 Reactor Context 에 실어 보내야 한다.
     *
     * <p><b>로깅이 왜 전부 {@code work} 안에 있어야 하는가.</b> {@code contextWrite} 는
     * <em>위쪽</em>(먼저 선언된 연산자)에만 적용된다. 아래에 있는 {@code subscribe} 콜백은
     * 원래 컨텍스트를 보므로 거기서 로그를 찍으면 traceId 가 다시 빈칸이 된다.
     */
    private void 관측하며실행(String name, Mono<?> work) {
        Observation observation = Observation.createNotStarted(name, observations).start();
        work.doOnError(observation::error)
                .doFinally(signal -> observation.stop())
                .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                .subscribe(ignored -> {
                }, error -> {
                    // 로깅은 이미 work 안에서 끝났다. 여기서 다시 찍으면 traceId 없이 중복된다.
                });
    }
}

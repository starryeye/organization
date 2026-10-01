package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
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
    private final DailyOnce dailyOnce;
    private final SyncMetrics metrics;
    private final ObservationRegistry observations;

    /**
     * 작업 락은 {@code FullSyncUseCase#start} 가 잡는다(설계 2026-09-30 §3). 여러 대가 같은 초에 불러도 한 대만 돌고, 나머지는 락을 못 잡아 기록 없이
     * 건너뛴다 — 오류가 아니다.
     */
    @Scheduled(cron = "${sync.cron}")
    public void 전체동기화() {
        관측하며실행("sync.ldap.full",
                Mono.defer(() -> fullSync.start(SyncTrigger.SCHEDULED, run -> {
                            metrics.record(run);
                            log.info("스케줄 동기화 완료: status={} written={} deleted={} failed={}",
                                    run.status(), run.writtenCount(), run.deletedCount(), run.failureCount());
                        }))
                        .onErrorResume(LockUnavailableException.class, busy -> {
                            log.info("다른 인스턴스가 동기화·재적재 중이라 이번 스케줄을 건너뛴다");
                            return Mono.empty();
                        })
                        .doOnError(error -> log.error("스케줄 동기화를 걸지 못했다", error)));
    }

    /**
     * 스냅샷은 테이블 TTL 을 쓰지 않으므로(최신까지 지워지면 다음 회차가 빈 기준선으로 돌아 삭제를 하나도 안 한다) 이 정리가
     * 보존 기간이 지난 스냅샷을 지우는 유일한 경로다(최신은 건너뛴다). 끄면(`sync.purge-cron: "-"`) 스냅샷(각 약 10만 아이템)이
     * 끝없이 쌓인다. 하루 1회 표지를 잡은 인스턴스만 돈다(설계 2026-09-30 §5.1).
     */
    @Scheduled(cron = "${sync.purge-cron}")
    public void 만료스냅샷정리() {
        관측하며실행("sync.ldap.purge",
                dailyOnce.run("ldap-purge", Mono.defer(snapshots::purgeExpired))
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
     *
     * <p><b>동기화 본체는 따로 돈다.</b> {@code work} 는 동기화를 거는 데서 끝나고 관측도 그때 닫힌다. 본체는 {@code SyncJobs} 가
     * 이 Reactor Context 를 이어받아 돌리므로 본체 로그에도 같은 traceId 가 붙는다.
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

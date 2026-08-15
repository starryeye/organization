package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
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

    @Scheduled(cron = "${sync.cron}")
    public void 전체동기화() {
        if (!executionGuard.tryAcquire()) {
            log.warn("이전 동기화가 아직 진행 중이라 이번 스케줄을 건너뛴다");
            return;
        }
        Mono.defer(() -> fullSync.execute(SyncTrigger.SCHEDULED))
                .doFinally(signal -> executionGuard.release())
                .subscribe(
                        run -> log.info("스케줄 동기화 완료: status={} written={} deleted={} failed={}",
                                run.status(), run.writtenCount(), run.deletedCount(), run.failureCount()),
                        error -> log.error("스케줄 동기화가 예기치 않게 실패했다", error));
    }

    /**
     * DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 명시적으로 정리한다.
     * 실제 AWS 에서는 TTL 이 처리하고 이 잡은 0건을 반환한다.
     */
    @Scheduled(cron = "${sync.purge-cron}")
    public void 만료스냅샷정리() {
        Mono.defer(snapshots::purgeExpired)
                .subscribe(
                        count -> log.info("만료 스냅샷 정리 완료: {}건", count),
                        error -> log.error("만료 스냅샷 정리에 실패했다", error));
    }
}

package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScheduler {

    private final SnapshotArchiveUseCase archive;
    private final TupleSnapshotRepository snapshots;

    /**
     * {@code Mono.defer} 로 감싸는 이유: 유스케이스가 Mono 를 반환하기 전에 동기 예외를 던지면
     * 그것이 스케줄러 메서드 밖으로 새어나가 아래 에러 컨슈머를 건너뛴다. 그러면 실패가
     * 로그에 남지 않는다.
     */
    @Scheduled(cron = "${sync.archive-cron}")
    public void 스냅샷아카이빙() {
        Mono.defer(archive::execute)
                .subscribe(
                        run -> log.info("스냅샷 아카이빙 완료: status={} snapshotId={}",
                                run.status(), run.snapshotId()),
                        error -> log.error("스냅샷 아카이빙이 예기치 않게 실패했다", error));
    }

    /** DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 명시적으로 정리한다. */
    @Scheduled(cron = "${sync.purge-cron}")
    public void 만료스냅샷정리() {
        Mono.defer(snapshots::purgeExpired)
                .subscribe(
                        count -> log.info("만료 스냅샷 정리 완료: {}건", count),
                        error -> log.error("만료 스냅샷 정리에 실패했다", error));
    }
}

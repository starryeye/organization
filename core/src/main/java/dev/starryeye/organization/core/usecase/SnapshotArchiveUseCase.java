package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * SCIM 인스턴스의 하루 1회 스냅샷 아카이빙.
 *
 * <p>SCIM 은 push 라 diff 용 스냅샷이 필요 없다. 이 스냅샷은 감사와 수동 복구용이며,
 * LDAP 인스턴스와 같은 저장 구조를 쓰므로 사고 조사 때 두 인스턴스의 기록을 같은 방식으로 읽는다.
 *
 * <p>SCIM push 요청과 달리 이것은 배치이므로 {@code SyncRun} 을 기록한다 —
 * 새벽에 아카이빙이 실패했다면 그 사실이 남아야 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class SnapshotArchiveUseCase {

    private final DirectoryStateRepository state;
    private final TupleSnapshotRepository snapshots;
    private final SyncRunRepository runs;
    private final Clock clock;

    public Mono<SyncRun> execute() {
        return runs.start(SyncSource.SCIM, SyncTrigger.ARCHIVE)
                .doOnNext(run -> log.info("[{}] 스냅샷 아카이빙 시작", run.runId()))
                .flatMap(run -> Mono.defer(this::archive)
                        .onErrorResume(error -> {
                            log.error("[{}] 스냅샷 아카이빙 실패", run.runId(), error);
                            return Mono.just(SyncOutcome.failed(error.getMessage()));
                        })
                        .flatMap(outcome -> runs.finish(run, outcome)));
    }

    private Mono<SyncOutcome> archive() {
        return state.loadAll().flatMap(directory -> {
            var mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            Instant now = clock.instant();
            TupleSnapshot snapshot = new TupleSnapshot(
                    SnapshotIds.generate(now, SyncSource.SCIM),
                    now,
                    SyncSource.SCIM,
                    mapping.tuples());

            return snapshots.save(snapshot)
                    .thenReturn(new SyncOutcome(SyncStatus.SUCCEEDED,
                            mapping.tuples().size(), 0, 0, snapshot.id(), null));
        });
    }
}

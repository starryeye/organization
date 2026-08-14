package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * LDAP 전체 동기화.
 *
 * <p>핵심은 <b>OpenFGA 에 먼저 쓰고, 실제 성공한 튜플만 새 스냅샷으로 커밋</b>하는 것이다.
 * 실패한 튜플은 새 스냅샷에 들어가지 않으므로 다음 동기화의 diff 가 자동으로 다시 잡는다.
 * 재시도 큐도 상태머신도 필요 없는 이유가 이것이다.
 */
@Slf4j
@RequiredArgsConstructor
public class FullSyncUseCase {

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final SyncRunRepository runs;
    private final DeletionGuard guard;
    private final Clock clock;

    public Mono<SyncRun> execute(SyncTrigger trigger) {
        return runs.start(SyncSource.LDAP, trigger)
                .flatMap(run -> synchronize(trigger)
                        .onErrorResume(error -> {
                            log.error("[{}] 전체 동기화 실패", run.runId(), error);
                            return Mono.just(SyncOutcome.failed(error.getMessage()));
                        })
                        .flatMap(outcome -> runs.finish(run, outcome)));
    }

    private Mono<SyncOutcome> synchronize(SyncTrigger trigger) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return baseline().flatMap(baseline -> {
                TupleDelta delta = TupleDiff.between(baseline, mapping.tuples());
                if (delta.isEmpty()) {
                    log.info("변경 없음. OpenFGA 를 호출하지 않는다");
                    return state.replaceWith(directory).thenReturn(SyncOutcome.noChange());
                }
                if (trigger != SyncTrigger.FORCED) {
                    GuardDecision decision = guard.evaluate(delta, baseline);
                    if (decision.aborted()) {
                        log.warn("삭제 가드 발동: {}", decision.message());
                        return Mono.just(SyncOutcome.aborted(decision.message()));
                    }
                }
                return writer.apply(delta)
                        .flatMap(result -> commit(directory, baseline, result));
            });
        });
    }

    private Mono<Set<RelationTuple>> baseline() {
        return snapshots.findLatest()
                .map(TupleSnapshot::tuples)
                .defaultIfEmpty(Set.of());
    }

    /**
     * 튜플 스냅샷과 현재상태는 <b>기준이 다르다</b>.
     * 스냅샷은 OpenFGA 에 실제 반영된 것, 현재상태는 LDAP 에서 읽은 사실 그대로다.
     */
    private Mono<SyncOutcome> commit(DirectorySnapshot directory,
                                     Set<RelationTuple> baseline,
                                     TupleWriteResult result) {
        Set<RelationTuple> committed = new HashSet<>(baseline);
        committed.removeAll(result.deleted());
        committed.addAll(result.written());

        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP),
                now,
                SyncSource.LDAP,
                committed);

        return snapshots.save(snapshot)
                .then(state.replaceWith(directory))
                .thenReturn(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshot.id())
                        : SyncOutcome.succeeded(result, snapshot.id()));
    }
}

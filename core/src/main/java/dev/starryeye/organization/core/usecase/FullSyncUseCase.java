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
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LDAP 전체 동기화.
 *
 * <p>핵심은 <b>OpenFGA 에 먼저 쓰고, 실제 성공한 튜플만 새 스냅샷으로 커밋</b>하는 것이다.
 * 실패한 튜플은 새 스냅샷에 들어가지 않으므로 다음 동기화의 diff 가 자동으로 다시 잡는다.
 *
 * <p><b>기록 중 표시(설계 2026-09-30 §4).</b> OpenFGA 에 쓰기 직전 스냅샷 포인터에 "기록 중"을 남기고, 스냅샷 저장이 그것을 지운다. 표시가 남아
 * 있으면 지난 회차가 쓴 뒤·기록 전에 멈춘 것이라 기준선을 믿을 수 없다 — 이번 회차는 비교 대신 장부를 훑어 맞춘다({@link LedgerAlignment}).
 * 그 사이 되돌려진 사람의 권한이 영원히 남거나 빠지는 일(점검 M2)이 이것으로 막힌다.
 */
@Slf4j
public class FullSyncUseCase {

    static final String 기준선_의심 = "기준선 의심(지난 회차가 기록 전에 멈춤) — 장부를 훑어 맞춤";

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final DeletionGuard guard;
    private final SyncJobs jobs;
    private final Clock clock;
    private final LedgerAlignment alignment;

    public FullSyncUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                           DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                           DeletionGuard guard, SyncJobs jobs, Clock clock) {
        this.source = source;
        this.snapshots = snapshots;
        this.state = state;
        this.writer = writer;
        this.guard = guard;
        this.jobs = jobs;
        this.clock = clock;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, guard, clock);
    }

    /**
     * 작업 락(SYNC)을 잡고 실행 기록(RUNNING)을 연 뒤 동기화를 요청과 떼어 띄운다(설계 2026-09-30 §3). 못 잡으면
     * {@link LockUnavailableException} — 다른 인스턴스가 동기화·재적재 중이다. {@code onFinished} 는 끝난 기록으로 불린다(지표·로그).
     */
    public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
        return jobs.startLocked(SyncSource.LDAP, trigger, MutationLock.LockPurpose.SYNC,
                Mono.defer(() -> synchronize(trigger)), onFinished);
    }

    private Mono<SyncOutcome> synchronize(SyncTrigger trigger) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return snapshots.isWriting().flatMap(writing -> writing
                    ? 훑어_맞춘다(directory, mapping.tuples(), trigger)
                    : 비교해_맞춘다(directory, mapping.tuples(), trigger));
        });
    }

    /**
     * 기준선을 믿을 수 없는 회차 — 재적재와 같은 청소로 한 번 맞춘다. {@code FORCED} 면 삭제 가드를 건너뛴다.
     *
     * <p>청소 자체가 오류로 끝나도(쓰기 단계 차단기, 스냅샷 저장 실패, "기록 중" 표시를 남기지 못함) 기록 메시지에 "기준선 의심" 사정을
     * 남긴다 — 그러지 않으면 FAILED 사유만 보고는 지난 회차가 기록 전에 멈췄다는 맥락이 사라진다. 취소는 {@code onErrorMap} 이
     * 보지 못하므로 여기서 감싸지 않는다.
     */
    private Mono<SyncOutcome> 훑어_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired, SyncTrigger trigger) {
        log.warn(기준선_의심);
        return alignment.align(directory, desired, trigger == SyncTrigger.FORCED)
                .map(outcome -> outcome.withNote(기준선_의심))
                .onErrorMap(error -> new IllegalStateException(
                        기준선_의심 + " / " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()),
                        error));
    }

    private Mono<SyncOutcome> 비교해_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired, SyncTrigger trigger) {
        return baseline().flatMap(baseline -> {
            TupleDelta delta = TupleDiff.between(baseline, desired);
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
            // 표시가 쓰기보다 먼저다 — 표시를 남기지 못하면 쓰지 않는다(설계 2026-09-30 §11)
            return snapshots.markWriting()
                    .then(Mono.defer(() -> writer.apply(delta)))
                    .flatMap(result -> commit(directory, baseline, result))
                    .onErrorResume(TupleWriteAbortedException.class, stopped ->
                            saveSnapshotAndState(directory, baseline, stopped.partial())
                                    .map(snapshotId -> SyncOutcome.stopped(
                                            stopped.partial(), snapshotId, stopped.getMessage())));
        });
    }

    private Mono<Set<RelationTuple>> baseline() {
        return snapshots.findLatest()
                .map(TupleSnapshot::tuples)
                .defaultIfEmpty(Set.of());
    }

    private Mono<SyncOutcome> commit(DirectorySnapshot directory,
                                     Set<RelationTuple> baseline,
                                     TupleWriteResult result) {
        return saveSnapshotAndState(directory, baseline, result)
                .map(snapshotId -> result.hasFailure()
                        ? SyncOutcome.partial(result, snapshotId)
                        : SyncOutcome.succeeded(result, snapshotId));
    }

    /**
     * 튜플 스냅샷과 현재상태는 <b>기준이 다르다</b>.
     * 스냅샷은 OpenFGA 에 실제 반영된 것, 현재상태는 LDAP 에서 읽은 사실 그대로다.
     *
     * <p>쓰기가 차단기로 멈췄을 때도 여기를 탄다(설계 2026-09-29 §5) — 이미 나간 쓰기를 스냅샷에 담지 않으면 기준선이 장부와 어긋난다.
     * 스냅샷 저장이 "기록 중" 표시를 지운다. 저장한 스냅샷 아이디를 준다.
     */
    private Mono<String> saveSnapshotAndState(DirectorySnapshot directory,
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
                .then(Mono.defer(() -> state.replaceWith(directory)))
                .thenReturn(snapshot.id());
    }
}

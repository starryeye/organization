package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectoryChanges;
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
 *
 * <p><b>이력(설계 2026-10-10 §4).</b> 회차가 커밋한 조직도 변경(전체 교체의 비교 결과)과 쓰고 지운 튜플을 회차 id 로 남긴다. 락을 반납한 뒤에 쓴다 —
 * {@link SyncJobs#startLockedThen} 의 "반납 뒤 할 일" 이다. 장부를 훑어 맞춘 회차는 지운 줄만 남긴다.
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
    private final AuditRecorder audit;
    private final LedgerAlignment alignment;

    /** 이력을 남기지 않는다 — 이력과 무관한 테스트가 쓴다. 운영 결선은 이력을 받는 생성자다. */
    public FullSyncUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                           DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                           DeletionGuard guard, SyncJobs jobs, Clock clock) {
        this(source, snapshots, state, writer, scanner, guard, jobs, clock, AuditRecorder.NONE);
    }

    public FullSyncUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                           DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                           DeletionGuard guard, SyncJobs jobs, Clock clock, AuditRecorder audit) {
        this.source = source;
        this.snapshots = snapshots;
        this.state = state;
        this.writer = writer;
        this.guard = guard;
        this.jobs = jobs;
        this.clock = clock;
        this.audit = audit;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, guard, clock, audit);
    }

    /**
     * 작업 락(SYNC)을 잡고 실행 기록(RUNNING)을 연 뒤 동기화를 요청과 떼어 띄운다(설계 2026-09-30 §3). 못 잡으면
     * {@link LockUnavailableException} — 다른 인스턴스가 동기화·재적재 중이다. {@code onFinished} 는 끝난 기록으로 불린다(지표·로그).
     */
    public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
        return jobs.startLockedThen(SyncSource.LDAP, trigger, MutationLock.LockPurpose.SYNC,
                run -> Mono.defer(() -> synchronize(trigger, run.runId())), onFinished);
    }

    /** 락 안에서 맞추고 "반납 뒤 할 일"(이력)을 내놓는다. */
    private Mono<Mono<SyncOutcome>> synchronize(SyncTrigger trigger, String runId) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return snapshots.isWriting().flatMap(writing -> writing
                    ? 훑어_맞춘다(directory, mapping.tuples(), trigger, runId)
                    : 비교해_맞춘다(directory, mapping.tuples(), trigger, runId));
        });
    }

    /**
     * 기준선을 믿을 수 없는 회차 — 재적재와 같은 청소로 한 번 맞춘다. {@code FORCED} 면 삭제 가드를 건너뛴다.
     *
     * <p>청소 자체가 오류로 끝나도(쓰기 단계 차단기, 스냅샷 저장 실패, "기록 중" 표시를 남기지 못함) 기록 메시지에 "기준선 의심" 사정을
     * 남긴다 — 그러지 않으면 FAILED 사유만 보고는 지난 회차가 기록 전에 멈췄다는 맥락이 사라진다. 취소는 {@code onErrorMap} 이
     * 보지 못하므로 여기서 감싸지 않는다.
     */
    private Mono<Mono<SyncOutcome>> 훑어_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                               SyncTrigger trigger, String runId) {
        log.warn(기준선_의심);
        return alignment.align(directory, desired, trigger == SyncTrigger.FORCED,
                        AuditCause.run(SyncSource.LDAP, AuditKind.LEDGER_ALIGNMENT, runId))
                .map(반납_뒤 -> 반납_뒤.map(outcome -> outcome.withNote(기준선_의심)))
                .onErrorMap(error -> new IllegalStateException(
                        기준선_의심 + " / " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()),
                        error));
    }

    private Mono<Mono<SyncOutcome>> 비교해_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                                SyncTrigger trigger, String runId) {
        AuditCause cause = AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, runId);
        return baseline().flatMap(baseline -> {
            TupleDelta delta = TupleDiff.between(baseline, desired);
            if (delta.isEmpty()) {
                log.info("변경 없음. OpenFGA 를 호출하지 않는다");
                return state.replaceWith(directory)
                        .map(changes -> 남긴다(cause, audit.now(), changes, TupleWriteResult.empty(), SyncOutcome.noChange()));
            }
            if (trigger != SyncTrigger.FORCED) {
                GuardDecision decision = guard.evaluate(delta, baseline);
                if (decision.aborted()) {
                    log.warn("삭제 가드 발동: {}", decision.message());
                    return Mono.just(Mono.just(SyncOutcome.aborted(decision.message())));
                }
            }
            // 표시가 쓰기보다 먼저다 — 표시를 남기지 못하면 쓰지 않는다(설계 2026-09-30 §11)
            return snapshots.markWriting()
                    .then(Mono.defer(() -> writer.apply(delta)))
                    .flatMap(result -> commit(directory, baseline, result, cause))
                    .onErrorResume(TupleWriteAbortedException.class, stopped ->
                            saveSnapshotAndState(directory, baseline, stopped.partial())
                                    .map(saved -> 남긴다(cause, saved.at(), saved.changes(), stopped.partial(),
                                            SyncOutcome.stopped(stopped.partial(), saved.snapshotId(), stopped.getMessage()))));
        });
    }

    private Mono<Set<RelationTuple>> baseline() {
        return snapshots.findLatest()
                .map(TupleSnapshot::tuples)
                .defaultIfEmpty(Set.of());
    }

    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, Set<RelationTuple> baseline,
                                           TupleWriteResult result, AuditCause cause) {
        return saveSnapshotAndState(directory, baseline, result)
                .map(saved -> 남긴다(cause, saved.at(), saved.changes(), result, result.hasFailure()
                        ? SyncOutcome.partial(result, saved.snapshotId())
                        : SyncOutcome.succeeded(result, saved.snapshotId())));
    }

    /**
     * 반납 뒤 할 일 — 회차가 커밋한 조직도 변경과 쓰고 지운 튜플을 회차 id 로 남긴다(설계 2026-10-10 §4.4). 시각 {@code at} 은 락 안에서 커밋한 직후에
     * 잡은 것이고, 줄은 락을 반납한 뒤에 만든다. 이력을 쓰지 못해도 회차는 그대로이고, 메시지 앞에 빠진 줄이 붙는다(§4.5).
     */
    private Mono<SyncOutcome> 남긴다(AuditCause cause, Instant at, DirectoryChanges changes, TupleWriteResult result,
                                  SyncOutcome outcome) {
        return Mono.defer(() -> audit.recordRun(cause, at,
                new AuditChanges().directory(changes).tuples(result).entries(), outcome));
    }

    /** 저장한 스냅샷 아이디와, 현재상태가 실제로 바뀐 것과, 그 커밋을 마친 시각(이력의 시각, §4.4). */
    private record 저장함(String snapshotId, DirectoryChanges changes, Instant at) {
    }

    /**
     * 튜플 스냅샷과 현재상태는 <b>기준이 다르다</b>.
     * 스냅샷은 OpenFGA 에 실제 반영된 것, 현재상태는 LDAP 에서 읽은 사실 그대로다.
     *
     * <p>쓰기가 차단기로 멈췄을 때도 여기를 탄다(설계 2026-09-29 §5) — 이미 나간 쓰기를 스냅샷에 담지 않으면 기준선이 장부와 어긋난다.
     * 스냅샷 저장이 "기록 중" 표시를 지운다. 저장한 스냅샷 아이디와 현재상태가 바뀐 것, 현재상태 교체를 마친 시각을 준다.
     */
    private Mono<저장함> saveSnapshotAndState(DirectorySnapshot directory,
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
                .map(changes -> new 저장함(snapshot.id(), changes, audit.now()));
    }
}

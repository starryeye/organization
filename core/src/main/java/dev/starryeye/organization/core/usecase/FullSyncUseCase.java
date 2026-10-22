package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.RunAudit;
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
 * <p>핵심은 <b>OpenFGA 에 먼저 쓰고, 반영됐다고 확실한 튜플만 새 스냅샷의 기준선(B)으로 커밋</b>하는 것이다.
 * 거절되거나 보내지 않은 쓰기는 B 에 들어가지 않으므로 다음 동기화의 diff 가 자동으로 다시 잡는다.
 *
 * <p><b>결과를 모르는 줄은 불확실 줄(U)로 남는다(설계 2026-10-20 §3).</b> 보냈는데 일시 오류로 결과를 모르는 쓰기·지우기는 서버가 반영하고 응답만 잃었을 수
 * 있다. 결과를 모르는 쓰기를 B 에서 빼 두면 그 줄이 목표에서 빠질 때 지울 기준이 없어 권한이 영원히 남고, 결과를 모르는 지우기를 B 에 두면 목표에 다시 들 때
 * 쓸 줄로 잡히지 않아 권한이 영원히 빠진다. 그래서 B 와 따로 U 에 적고, 다음 회차가 목표에 있으면 쓰고 없으면 지운다 — 쓸 줄 = D − B,
 * 지울 줄 = (B − D) ∪ (U − D). 삭제 가드의 기준 수는 |B| + |U| 다.
 *
 * <p><b>기록 중 표시(설계 2026-09-30 §4).</b> OpenFGA 에 쓰기 직전 스냅샷 포인터에 "기록 중"을 남기고, 스냅샷 저장이 그것을 지운다. 표시가 남아
 * 있으면 지난 회차가 쓴 뒤·기록 전에 멈춘 것이라 기준선을 믿을 수 없다 — 이번 회차는 비교 대신 장부를 훑어 맞춘다({@link LedgerAlignment}).
 * 그 사이 되돌려진 사람의 권한이 영원히 남거나 빠지는 일(점검 M2)이 이것으로 막힌다.
 *
 * <p><b>포인터가 없을 때도 기준선을 믿지 않는다(설계 2026-10-20 §4).</b> 첫 회차이거나 테이블을 새로 만들었다 — 후자는 이미 장부(store)에 줄이 있는데 빈 기준선과
 * 비교해 지울 줄이 없다고 잘못 보게 된다. 그래서 비교 대신 같은 장부 맞추기로 간다. 빈 장부의 첫 설치는 D 를 다 쓰고 장부를 한 번 훑을 뿐이다. 포인터 상태는 셋이다
 * — 없음(장부 맞추기, 이력은 동기화 그대로), 기록 중(장부 맞추기, 이력은 장부 맞추기), 있음(비교).
 *
 * <p><b>이력(설계 2026-10-10 §4, 2026-10-15).</b> 회차가 OpenFGA 에 쓰고 지운 튜플은 묶음마다, 현재상태가 바뀐 것은 직원·조직 하나마다 끝나는 즉시
 * 회차의 변경 모음에 더한다. 회차가 어떻게 끝나든 {@link SyncJobs} 가 락을 반납한 뒤 남긴다 — 스냅샷 저장·현재상태 교체가 실패하거나 리스를 잃어도
 * 이미 한 변경이 남는다. "기록 중" 때문에 장부를 훑어 맞춘 회차는 종류를 장부 맞추기로 바꾸고 지운 줄만 남긴다. 기준선이 없어 훑은 첫 회차는 종류가 동기화 그대로이고
 * 쓴 줄(첫 적재의 부여 이력)도 남긴다.
 */
@Slf4j
public class FullSyncUseCase {

    static final String 기준선_의심 = "기준선 의심(지난 회차가 기록 전에 멈춤) — 장부를 훑어 맞춤";
    static final String 기준선_없음 = "기준선 없음(첫 회차 또는 새 테이블) — 장부를 훑어 맞춤";

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
        return jobs.startLockedThen(SyncSource.LDAP, trigger, MutationLock.LockPurpose.SYNC, AuditKind.SYNC,
                (run, 기록) -> Mono.defer(() -> synchronize(trigger, 기록)), onFinished);
    }

    /** 락 안에서 맞추고 "반납 뒤 할 일"을 내놓는다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다. */
    private Mono<Mono<SyncOutcome>> synchronize(SyncTrigger trigger, RunAudit 기록) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return snapshots.baselineState().flatMap(상태 -> switch (상태) {
                // 포인터가 없다 — 첫 회차이거나 테이블을 새로 만들었다. 빈 기준선과 비교하면 장부의 찌꺼기를 영원히 못 지운다(설계 2026-10-20 §4)
                case NONE -> 기준선_없이_맞춘다(directory, mapping.tuples(), trigger, 기록);
                case WRITING -> 훑어_맞춘다(directory, mapping.tuples(), trigger, 기록);
                case PRESENT -> 비교해_맞춘다(directory, mapping.tuples(), trigger, 기록.changes());
            });
        });
    }

    /**
     * 기준선을 믿을 수 없는 회차 — 재적재와 같은 청소로 한 번 맞춘다. {@code FORCED} 면 삭제 가드를 건너뛴다.
     * 지난 회차가 쓴 뒤·기록 전에 멈춰 "기록 중" 표시가 남았다. 이력은 장부 맞추기로 바꾸고 지운 줄만 남긴다.
     */
    private Mono<Mono<SyncOutcome>> 훑어_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                               SyncTrigger trigger, RunAudit 기록) {
        log.warn(기준선_의심);
        기록.kind(AuditKind.LEDGER_ALIGNMENT);
        return 장부를_맞춘다(directory, desired, trigger, 기록, 기준선_의심, false);
    }

    /**
     * 기준선이 없는 회차 — 스냅샷 포인터가 아예 없다. 빈 기준선과 비교하는 대신 "기록 중"과 같은 청소로 맞춘다(설계 2026-10-20 §4).
     * 빈 장부의 첫 설치는 D 를 다 쓰고 장부를 한 번 훑을 뿐이라 결과가 같다.
     *
     * <p>이력은 동기화 그대로 두고 쓴 줄도 부여로 남긴다 — 첫 적재의 부여 이력(설계 2026-10-10 §4.2)을 잃지 않는다. 가드가 통째로 막으면 스냅샷이 없고
     * 표시가 남아, 다음 회차는 "기록 중" 길로 다시 장부를 훑는다.
     */
    private Mono<Mono<SyncOutcome>> 기준선_없이_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                                    SyncTrigger trigger, RunAudit 기록) {
        log.warn(기준선_없음);
        return 장부를_맞춘다(directory, desired, trigger, 기록, 기준선_없음, true);
    }

    /**
     * 장부 맞추기로 한 번 맞추고 결론 앞에 사정을 붙인다.
     *
     * <p>청소 자체가 오류로 끝나도(쓰기 단계 차단기, 스냅샷 저장 실패, "기록 중" 표시를 남기지 못함) 기록 메시지에 사정을 남긴다 — 그러지 않으면 FAILED
     * 사유만 보고는 기준선을 믿지 못해 훑었다는 맥락이 사라진다. 취소는 {@code onErrorMap} 이 보지 못하므로 여기서 감싸지 않는다.
     */
    private Mono<Mono<SyncOutcome>> 장부를_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                               SyncTrigger trigger, RunAudit 기록, String 사정, boolean 쓴_줄도_남긴다) {
        return alignment.align(directory, desired, trigger == SyncTrigger.FORCED, 기록.changes(), 쓴_줄도_남긴다)
                .map(반납_뒤 -> 반납_뒤.map(outcome -> outcome.withNote(사정)))
                .onErrorMap(error -> new IllegalStateException(
                        사정 + " / " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()),
                        error));
    }

    private Mono<Mono<SyncOutcome>> 비교해_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                                SyncTrigger trigger, AuditChanges 기록) {
        return baseline().flatMap(baseline -> {
            TupleDelta delta = 델타를_정한다(baseline, desired);
            if (delta.isEmpty()) {
                // 델타가 비면 U 도 비어 있다 — U 의 줄은 D 에 있으면 쓸 줄에, 없으면 지울 줄에 반드시 든다
                log.info("변경 없음. OpenFGA 를 호출하지 않는다");
                return state.replaceWith(directory, 기록.directoryListener())
                        .thenReturn(Mono.just(SyncOutcome.noChange()));
            }
            if (trigger != SyncTrigger.FORCED) {
                GuardDecision decision = guard.evaluate(delta.toDelete().size(), baseline.크기(), "기준 스냅샷");
                if (decision.aborted()) {
                    log.warn("삭제 가드 발동: {}", decision.message());
                    return Mono.just(Mono.just(SyncOutcome.aborted(decision.message())));
                }
            }
            // 표시가 쓰기보다 먼저다 — 표시를 남기지 못하면 쓰지 않는다(설계 2026-09-30 §11). 쓴·지운 줄은 묶음마다 기록에 더한다
            return snapshots.markWriting()
                    .then(Mono.defer(() -> writer.apply(delta, 기록::tuples)))
                    .flatMap(result -> commit(directory, baseline, result, 기록))
                    .onErrorResume(TupleWriteAbortedException.class, stopped ->
                            saveSnapshotAndState(directory, baseline, stopped.partial(), 기록)
                                    .map(snapshotId -> Mono.just(
                                            SyncOutcome.stopped(stopped.partial(), snapshotId, stopped.getMessage()))));
        });
    }

    /** 기준선 — 직전 스냅샷의 B(장부에 있다고 확실히 아는 줄)와 U(결과를 몰라 있는지 모르는 줄). 스냅샷이 없으면 둘 다 비었다. */
    private record 기준선(Set<RelationTuple> tuples, Set<RelationTuple> uncertain) {

        static final 기준선 없음 = new 기준선(Set.of(), Set.of());

        /** 삭제 가드의 기준 수 — |B| + |U|. 불확실 줄도 장부에 있을 수 있는 줄이다 */
        int 크기() {
            return tuples.size() + uncertain.size();
        }
    }

    private Mono<기준선> baseline() {
        return snapshots.findLatest()
                .map(snapshot -> new 기준선(snapshot.tuples(), snapshot.uncertain()))
                .defaultIfEmpty(기준선.없음);
    }

    /**
     * 쓸 줄 = D − B, 지울 줄 = (B − D) ∪ (U − D)(설계 2026-10-20 §3.3). U 는 B 에 없으므로 U ∩ D 는 쓸 줄에 저절로 든다. 멱등 옵션(없는 줄 지우기 무시,
     * 있는 줄 쓰기 무시) 덕에 U 의 줄을 다시 보내도 안전하다. 쓸 줄은 D 안, 지울 줄은 D 밖이라 겹치지 않는다.
     */
    private static TupleDelta 델타를_정한다(기준선 baseline, Set<RelationTuple> desired) {
        TupleDelta diff = TupleDiff.between(baseline.tuples(), desired);
        if (baseline.uncertain().isEmpty()) {
            return diff;
        }
        Set<RelationTuple> toDelete = new HashSet<>(diff.toDelete());
        for (RelationTuple tuple : baseline.uncertain()) {
            if (!desired.contains(tuple)) {
                toDelete.add(tuple);
            }
        }
        return new TupleDelta(diff.toWrite(), toDelete);
    }

    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, 기준선 baseline,
                                           TupleWriteResult result, AuditChanges 기록) {
        return saveSnapshotAndState(directory, baseline, result, 기록)
                .map(snapshotId -> Mono.just(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshotId)
                        : SyncOutcome.succeeded(result, snapshotId)));
    }

    /**
     * 튜플 스냅샷과 현재상태는 <b>기준이 다르다</b>.
     * 스냅샷은 OpenFGA 에 반영됐다고 확실한 줄(B)과 결과를 모르는 줄(U), 현재상태는 LDAP 에서 읽은 사실 그대로다.
     *
     * <p>쓰기가 차단기로 멈췄을 때도 여기를 탄다(설계 2026-09-29 §5) — 이미 나간 쓰기를 스냅샷에 담지 않으면 기준선이 장부와 어긋난다. 나간 쓰기 가운데
     * 결과를 모르는 줄은 확실하다고 할 수 없다(설계 2026-09-30 §4.1 의 "기준선이 정확하다"는 이 줄을 몰랐다) — <b>결과를 모르는 줄은 U 로 남는다</b>.
     * R 을 이번 회차의 결과라 하면(설계 2026-10-20 §3.2):
     * <ul>
     *   <li>U_new = (U − (R.쓴 줄 ∪ R.지운 줄)) ∪ R.불확실 줄 — 이번에 결과가 확정된 줄만 U 에서 나간다. 다시 보냈는데 거절되거나 보내지 않은 줄은 여전히
     *       모르므로 U 에 남는다.</li>
     *   <li>B_new = ((B − R.지운 줄) ∪ R.쓴 줄) − U_new — B 와 U 는 겹치지 않는다.</li>
     * </ul>
     * 스냅샷 저장이 "기록 중" 표시를 지운다. 현재상태가 바뀐 것은 직원·조직 하나마다 {@code 기록} 에 더한다. 저장한
     * 스냅샷 아이디를 준다.
     */
    private Mono<String> saveSnapshotAndState(DirectorySnapshot directory, 기준선 baseline,
                                              TupleWriteResult result, AuditChanges 기록) {
        Set<RelationTuple> uncertain = new HashSet<>(baseline.uncertain());
        uncertain.removeAll(result.written());
        uncertain.removeAll(result.deleted());
        uncertain.addAll(result.uncertain());

        Set<RelationTuple> committed = new HashSet<>(baseline.tuples());
        committed.removeAll(result.deleted());
        committed.addAll(result.written());
        committed.removeAll(uncertain);

        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP),
                now,
                SyncSource.LDAP,
                committed,
                uncertain);

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory, 기록.directoryListener())))
                .thenReturn(snapshot.id());
    }
}

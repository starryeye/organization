package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * LDAP 장부 맞추기 — 재적재와 "멈춘 뒤 첫 동기화"가 함께 쓴다(설계 2026-09-30 §4).
 *
 * <ol>
 *   <li>쓰기 전에 "기록 중" 표시를 남긴다 — 이 청소가 중간에 멈춰도 다음 회차가 다시 맞춘다.</li>
 *   <li>장부를 D 에 맞추되, 지우기 전에 삭제 가드를 본다({@code force} 면 건너뛴다). 가드에 걸리면 지우지 않고 ABORTED — 스냅샷을 남기지 않으므로
 *       표시가 남아 다음 회차도 다시 확인한다.</li>
 *   <li>끝나면 스냅샷(표시가 사라진다)과 현재상태를 남긴다.</li>
 * </ol>
 */
@RequiredArgsConstructor
final class LedgerAlignment {

    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final DeletionGuard guard;
    private final Clock clock;

    Mono<SyncOutcome> align(DirectorySnapshot directory, Set<RelationTuple> desired, boolean force) {
        TupleReconciler.DeleteCheck check = force ? TupleReconciler.DeleteCheck.NONE : this::가드로_본다;
        return snapshots.markWriting()
                .then(TupleReconciler.reconcile(writer, scanner, desired, check))
                .flatMap(reconciliation -> reconciliation.held()
                        ? Mono.just(SyncOutcome.aborted(reconciliation.heldReason()))
                        : commit(directory, reconciliation));
    }

    private Optional<String> 가드로_본다(Set<RelationTuple> desired, Set<RelationTuple> stale, long scanned) {
        GuardDecision decision = guard.evaluate(stale.size(), Math.toIntExact(scanned), "훑은 장부");
        return decision.aborted() ? Optional.of(decision.message()) : Optional.empty();
    }

    /** 스냅샷에는 장부에 실제로 있다고 볼 줄을 담는다(설계 2026-09-29 §3.1 4단계). 그다음 현재상태를 LDAP 대로 바꾼다. */
    private Mono<SyncOutcome> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger());

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory)))
                .thenReturn(reconciliation.outcome(snapshot.id()));
    }
}

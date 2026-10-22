package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditChanges;
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
 *   <li>지운 줄과 현재상태가 바뀐 것은 끝나는 즉시 회차의 변경 모음에 더한다(설계 2026-10-15 §5) — 스냅샷 저장·현재상태 교체가 실패해도 남는다. 쓴 줄은
 *       중복 무시로 써 이미 있던 줄과 가를 수 없어 남기지 않는다(설계 2026-10-10 §3.5). 이력은 {@link SyncJobs} 가 남긴다.</li>
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

    /** 락 안에서 맞추고 "반납 뒤 할 일"을 내놓는다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다. */
    Mono<Mono<SyncOutcome>> align(DirectorySnapshot directory, Set<RelationTuple> desired, boolean force,
                                  AuditChanges 기록) {
        TupleReconciler.DeleteCheck check = force ? TupleReconciler.DeleteCheck.NONE : this::가드로_본다;
        return snapshots.markWriting()
                .then(TupleReconciler.reconcile(writer, scanner, desired, TupleReconciler.DesiredRevision.NONE, check,
                        기록::revoked))
                .flatMap(reconciliation -> reconciliation.held()
                        ? Mono.just(Mono.just(SyncOutcome.aborted(reconciliation.heldReason())))
                        : commit(directory, reconciliation, 기록));
    }

    private Optional<String> 가드로_본다(Set<RelationTuple> desired, Set<RelationTuple> stale, long scanned) {
        GuardDecision decision = guard.evaluate(stale.size(), Math.toIntExact(scanned), "훑은 장부");
        return decision.aborted() ? Optional.of(decision.message()) : Optional.empty();
    }

    /**
     * 스냅샷에는 장부에 있다고 확실히 아는 줄(B)과, 보냈는데 결과를 몰라 있는지 모르는 줄(U)을 따로 담는다(설계 2026-09-29 §3.1 4단계, 2026-10-20 §3.4).
     * 그다음 현재상태를 LDAP 대로 바꾼다.
     */
    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation,
                                           AuditChanges 기록) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger(),
                reconciliation.uncertain());

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory, 기록.directoryListener())))
                .thenReturn(Mono.just(reconciliation.outcome(snapshot.id())));
    }
}

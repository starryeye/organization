package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LDAP 장부 맞추기 — 재적재와 "멈춘 뒤 첫 동기화", "기준선 없는 첫 회차"가 함께 쓴다(설계 2026-09-30 §4, 2026-10-20 §4).
 *
 * <ol>
 *   <li>쓰기 전에 "기록 중" 표시를 남긴다 — 이 청소가 중간에 멈춰도 다음 회차가 다시 맞춘다.</li>
 *   <li>장부를 D 에 맞추되, 지우기 전에 삭제 가드를 본다({@code force} 면 건너뛴다). 기준 수는 훑은 줄 수다.
 *     <ul>
 *       <li>전부 보류면 지우지 않고 ABORTED — 스냅샷을 남기지 않으므로 표시가 남아 다음 회차도 다시 확인한다. D 는 이미 썼으므로 쓴 수는 실행 기록에
 *           남긴다({@link SyncOutcome#held}, 설계 2026-10-27 §12).</li>
 *       <li>가드가 걸렸지만 디렉터리가 막았다고 명시한 회수는 가드 안이면(설계 2026-10-20 §5.4) 그것만 지운다. 스냅샷을 남겨 표시를 지우고 —
 *           보류한 후보는 장부에 그대로 있으니 기준선(B)에 든다 — 다음 회차는 비교 경로로 간다. 그 회차는 같은 판정을 받지 않는다: 지운 회수가 B 에서
 *           빠졌으므로 남은 지우기를 줄어든 기준 수에 대어 가드를 다시 보고, 그사이 새로 막힌 계정이 없으면 비활성 부분이 비어 대개 전부 보류다(남은 비율이
 *           임계 아래로 내려가면 그대로 반영된다). 현재상태는 바꾸지 않는다. 이 길은 가드를 보기 전에 D 를 이미 다 썼으므로 보류할 쓰기는 없다.</li>
 *     </ul>
 *   </li>
 *   <li>끝나면 스냅샷(표시가 사라진다)과 현재상태를 남긴다. 지우기가 차단기로 멈췄어도 일부만 반영한 회차가 아니면 현재상태는 바뀌므로, 그 회차의 메시지
 *       끝에 {@link FullSyncUseCase#현재상태_교체} 를 적는다.</li>
 *   <li>지운 줄과 현재상태가 바뀐 것은 끝나는 즉시 회차의 변경 모음에 더한다(설계 2026-10-15 §5) — 스냅샷 저장·현재상태 교체가 실패해도 남는다. 쓴 줄은
 *       중복 무시로 써 이미 있던 줄과 가를 수 없어 보통 남기지 않는다(설계 2026-10-10 §3.5). 다만 기준선 없는 첫 회차는 첫 적재의 부여 이력을 잃으면 안
 *       되므로({@code 쓴_줄도_남긴다}) 쓴 줄도 묶음마다 더한다(설계 2026-10-20 §4). 이력은 {@link SyncJobs} 가 남긴다.</li>
 * </ol>
 */
@RequiredArgsConstructor
final class LedgerAlignment {

    /** 가드가 걸려 비활성 회수만 반영한 회차의 결론(설계 2026-10-20 §5.3). 뒤에 " / " 와 초과 문구를 잇는다. */
    static final String 비활성_회수만 = "삭제 가드 발동 — 비활성 회수 %d건만 반영하고 나머지 지우기 %d건·쓰기 %d건은 보류";

    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final DeletionGuard guard;
    private final Clock clock;

    /**
     * 가드가 걸려 비활성 회수만 반영한 회차의 결론 — §5.3 문구 뒤에 전체 지우기의 초과 문구를 잇는다.
     *
     * @param 반영        반영한 비활성 회수
     * @param 나머지_지우기 보류한 나머지 지우기
     * @param 쓰기        보류한 쓰기(장부 맞추기 경로는 가드 전에 다 썼으므로 0)
     * @param 초과        가드의 초과 문구
     */
    static String 비활성_회수만_반영했다(int 반영, int 나머지_지우기, int 쓰기, String 초과) {
        return 비활성_회수만.formatted(반영, 나머지_지우기, 쓰기) + " / " + 초과;
    }

    /** 쓴 줄은 남기지 않는 장부 맞추기 — 재적재와 "기록 중" 회차. */
    Mono<Mono<SyncOutcome>> align(DirectorySnapshot directory, Set<RelationTuple> desired, boolean force,
                                  AuditChanges 기록) {
        return align(directory, desired, force, 기록, false);
    }

    /**
     * 락 안에서 맞추고 "반납 뒤 할 일"을 내놓는다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다.
     *
     * @param 쓴_줄도_남긴다 true 면 쓴 줄도 묶음마다 부여로 더한다 — 기준선 없는 첫 회차(설계 2026-10-20 §4). 그 회차는 종류가 동기화 그대로라 첫 적재의
     *                       부여 이력이 사라지면 안 된다
     */
    Mono<Mono<SyncOutcome>> align(DirectorySnapshot directory, Set<RelationTuple> desired, boolean force,
                                  AuditChanges 기록, boolean 쓴_줄도_남긴다) {
        TupleReconciler.DeleteCheck check = force
                ? TupleReconciler.DeleteCheck.NONE
                : (최종_D, stale, scanned) -> 가드로_본다(directory, stale, scanned);
        Consumer<TupleWriteResult> 쓴_묶음마다 = 쓴_줄도_남긴다 ? 기록::tuples : 묶음 -> {
        };
        return snapshots.markWriting()
                .then(TupleReconciler.reconcile(writer, scanner, desired, TupleReconciler.DesiredRevision.NONE, check,
                        기록::revoked, 쓴_묶음마다))
                .flatMap(reconciliation -> reconciliation.heldEntirely()
                        ? Mono.just(Mono.just(SyncOutcome.held(reconciliation.result(), reconciliation.heldReason())))
                        : commit(directory, reconciliation, 기록));
    }

    /**
     * 훑은 장부를 기준으로 삭제 가드를 본다(설계 2026-10-20 §5.4). 전체가 넘었지만 비활성 회수만으로는 가드 안이면 그것만 지우게 하고, 결론에 무엇을
     * 반영·보류했는지 적는다.
     */
    private TupleReconciler.DeleteVerdict 가드로_본다(DirectorySnapshot directory, Set<RelationTuple> stale, long scanned) {
        GuardDecision decision = guard.evaluate(stale, TupleMapper.inactiveMemberTuples(directory),
                Math.toIntExact(scanned), "훑은 장부");
        if (decision.partial()) {
            Set<RelationTuple> 회수 = decision.revocationsOnly();
            return TupleReconciler.DeleteVerdict.partial(회수,
                    비활성_회수만_반영했다(회수.size(), stale.size() - 회수.size(), 0, decision.message()));
        }
        return decision.aborted()
                ? TupleReconciler.DeleteVerdict.holdAll(decision.message())
                : TupleReconciler.DeleteVerdict.proceed(stale);
    }

    /**
     * 스냅샷에는 장부에 있다고 확실히 아는 줄(B)과, 보냈는데 결과를 몰라 있는지 모르는 줄(U)을 따로 담는다(설계 2026-09-29 §3.1 4단계, 2026-10-20 §3.4).
     * 그다음 현재상태를 LDAP 대로 바꾼다 — 가드가 걸려 비활성 회수만 반영했으면 바꾸지 않는다(설계 2026-10-20 §5.4). 가드가 걸린 회차는 디렉터리 그림을
     * 받아들이지 않은 회차다.
     */
    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation,
                                           AuditChanges 기록) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger(),
                reconciliation.uncertain());

        Mono<Void> 현재상태 = reconciliation.revokedOnly()
                ? Mono.empty()
                : Mono.defer(() -> state.replaceWith(directory, 기록.directoryListener())).then();
        return snapshots.save(snapshot)
                .then(현재상태)
                .thenReturn(Mono.just(결론(reconciliation, snapshot.id())));
    }

    /**
     * 실행 기록의 결론. 지우기가 차단기로 멈췄지만 현재상태는 실제로 교체한 회차({@code !revokedOnly()})는 메시지 끝에 그 사실을 적는다(설계
     * 2026-10-27 §12). 일부만 반영한 회차는 현재상태를 바꾸지 않았으므로 적지 않는다.
     */
    private static SyncOutcome 결론(TupleReconciler.Reconciliation reconciliation, String snapshotId) {
        SyncOutcome outcome = reconciliation.outcome(snapshotId);
        return !reconciliation.revokedOnly() && reconciliation.stopReason() != null
                ? outcome.withSuffix(FullSyncUseCase.현재상태_교체)
                : outcome;
    }
}

package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.GuardHold;
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
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LDAP 장부 맞추기 — 재적재와 "멈춘 뒤 첫 동기화", "기준선 없는 첫 회차"가 함께 쓴다(설계 2026-09-30 §4, 2026-10-20 §4).
 *
 * <ol>
 *   <li>쓰기 전에 "기록 중" 표시를 남긴다 — 이 청소가 중간에 멈춰도 다음 회차가 다시 맞춘다.</li>
 *   <li>장부를 D 에 맞추되, 지우기 전에 이번 회차의 삭제 가드를 본다. 기준 수는 훑은 줄 수다. 강제 회차도 건너뛰지 않는다 — 허용 수 N 을 상한으로 같은
 *       가드를 본다({@link DeletionGuard#forced}, 설계 2026-11-20 F1·F2).
 *     <ul>
 *       <li>전부 보류면 지우지 않고 ABORTED — 스냅샷을 남기지 않으므로 표시가 남아 다음 회차도 다시 확인한다. D 는 이미 썼으므로 쓴 수는 실행 기록에
 *           남긴다({@link SyncOutcome#held}, 설계 2026-10-27 §12).</li>
 *       <li>가드가 걸렸지만 디렉터리가 막았다고 명시한 회수는 가드 안이면(설계 2026-10-20 §5.4) 그것만 지운다. 스냅샷을 남겨 표시를 지우고 —
 *           보류한 후보는 장부에 그대로 있으니 기준선(B)에 든다 — 다음 회차는 비교 경로로 간다. 그 회차는 같은 판정을 받지 않는다: 지운 회수가 B 에서
 *           빠졌으므로 남은 지우기를 줄어든 기준 수에 대어 가드를 다시 보고, 그사이 새로 막힌 계정이 없으면 비활성 부분이 비어 대개 전부 보류다(남은 비율이
 *           임계치 아래로, 남은 줄 수가 상한 아래로 내려가면 그대로 반영된다). 현재상태는 바꾸지 않는다. 이 길은 가드를 보기 전에 D 를 이미 다 썼으므로 보류할 쓰기는 없다.</li>
 *     </ul>
 *   </li>
 *   <li>끝나면 스냅샷(표시가 사라진다)과 현재상태를 남긴다. 지우기가 차단기로 멈췄어도 일부만 반영한 회차가 아니면 현재상태는 바뀌므로, 그 회차의 메시지
 *       끝에 {@link FullSyncUseCase#현재상태_교체} 를 적는다.</li>
 *   <li>지운 줄과 현재상태가 바뀐 것은 끝나는 즉시 회차의 변경 모음에 더한다(설계 2026-10-15 §5) — 스냅샷 저장·현재상태 교체가 실패해도 남는다. 쓴 줄은
 *       중복 무시로 써 이미 있던 줄과 가를 수 없어 보통 남기지 않는다(설계 2026-10-10 §3.5). 다만 기준선 없는 첫 회차는 첫 적재의 부여 이력을 잃으면 안
 *       되므로({@code 쓴_줄도_남긴다}) 쓴 줄도 묶음마다 더한다(설계 2026-10-20 §4). 이력은 {@link SyncJobs} 가 남긴다.</li>
 * </ol>
 *
 * <p><b>재활성 가드(설계 2026-11-24 R4·R8, §6).</b> 판정은 부르는 쪽(동기화의 기록 중 회차, 재적재)이 매핑 앞에서 한다. 여기 오는 그림과 D 는 판정이 정한
 * 것이다 — 재활성을 보류했으면 그 직원들을 비활성으로 되돌린 그림′ 이라, 삭제 가드의 비활성 회수·멈춤 내역·현재상태 교체·스냅샷의 비활성 소속 줄(I)이 모두
 * 그림′ 을 본다. 커밋(일부 반영 포함)은 받은 그림의 I 를 스냅샷에 남긴다. <b>한계:</b> 이 길은 가드를 보기 전에 D 를 다 쓰므로, 삭제 가드가 전부 보류하면
 * 이번 회차에 통과한(문턱 아래이거나 강제로 넘긴) 재활성 직원의 줄은 장부에 나갔는데 스냅샷이 남지 않아 I 는 옛것이다. 다음 회차가 같은 직원을 다시
 * 재활성으로 세고, 보류하면 방금 나간 줄을 비활성 회수로 지운다. 막는 두 길(전부 보류에서도 I 저장, 쓰기 전 판정 순서 변경) 모두 드문 겹침에 비해 커서
 * 받아들였다 — 운영 절차가 두 수를 함께 주게 한다(설계 2026-11-24 §3.6·§12).
 */
@Slf4j
@RequiredArgsConstructor
final class LedgerAlignment {

    /** 가드가 걸려 비활성 회수만 반영한 회차의 결론(설계 2026-10-20 §5.3). 뒤에 " / " 와 초과 문구를 잇는다. */
    static final String 비활성_회수만 = "삭제 가드 발동 — 비활성 회수 %d건만 반영하고 나머지 지우기 %d건·쓰기 %d건은 보류";

    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
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
    Mono<Mono<SyncOutcome>> align(DirectorySnapshot directory, Set<RelationTuple> desired, DeletionGuard guard,
                                  AuditChanges 기록) {
        return align(directory, desired, guard, 기록, false);
    }

    /**
     * 락 안에서 맞추고 "반납 뒤 할 일"을 내놓는다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다.
     *
     * @param guard        이번 회차의 삭제 가드 — 설정의 가드이거나, 강제 회차면 허용 수 N 을 상한으로 한 가드다(설계 2026-11-20 F2). 늘 본다
     * @param 쓴_줄도_남긴다 true 면 쓴 줄도 묶음마다 부여로 더한다 — 기준선 없는 첫 회차(설계 2026-10-20 §4). 그 회차는 종류가 동기화 그대로라 첫 적재의
     *                       부여 이력이 사라지면 안 된다
     */
    Mono<Mono<SyncOutcome>> align(DirectorySnapshot directory, Set<RelationTuple> desired, DeletionGuard guard,
                                  AuditChanges 기록, boolean 쓴_줄도_남긴다) {
        // 가드가 걸리면 콜백이 내역을 여기에 담는다 — 맞추기 기계(TupleReconciler)는 사유 문자열만 안다(설계 2026-11-20 §3.1)
        AtomicReference<GuardHold> 내역 = new AtomicReference<>();
        TupleReconciler.DeleteCheck check = (최종_D, stale, scanned) -> 가드로_본다(guard, directory, stale, scanned, 내역);
        Consumer<TupleWriteResult> 쓴_묶음마다 = 쓴_줄도_남긴다 ? 기록::tuples : 묶음 -> {
        };
        return snapshots.markWriting()
                .then(TupleReconciler.reconcile(writer, scanner, desired, TupleReconciler.DesiredRevision.NONE, check,
                        기록::revoked, 쓴_묶음마다))
                .flatMap(reconciliation -> reconciliation.heldEntirely()
                        ? Mono.just(Mono.just(SyncOutcome.held(reconciliation.result(), reconciliation.heldReason())
                                .withGuardHold(내역.get())))
                        : commit(directory, reconciliation, 내역.get(), 기록));
    }

    /**
     * 훑은 장부를 기준으로 삭제 가드를 본다(설계 2026-10-20 §5.4). 전체가 넘었지만 비활성 회수만으로는 가드 안이면 그것만 지우게 하고, 결론에 무엇을
     * 반영·보류했는지 적는다.
     */
    private static TupleReconciler.DeleteVerdict 가드로_본다(DeletionGuard guard, DirectorySnapshot directory,
                                                                Set<RelationTuple> stale, long scanned,
                                                                AtomicReference<GuardHold> 내역) {
        GuardDecision decision = guard.evaluate(stale, TupleMapper.inactiveMemberTuples(directory),
                Math.toIntExact(scanned), "훑은 장부");
        if (decision.aborted()) {
            GuardHold hold = GuardHold.of(stale, directory);
            내역.set(hold);
            log.warn("삭제 가드 내역: {}", hold.summary());
        }
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
     * 비활성 소속 줄(I)은 받은 그림의 것이다 — 재활성을 보류한 회차면 그림′ 이다(설계 2026-11-24 R1·R4). 일부 반영도 같다.
     * 그다음 현재상태를 LDAP 대로 바꾼다 — 가드가 걸려 비활성 회수만 반영했으면 바꾸지 않는다(설계 2026-10-20 §5.4). 가드가 걸린 회차는 디렉터리 그림을
     * 받아들이지 않은 회차다.
     */
    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation,
                                           GuardHold hold, AuditChanges 기록) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger(),
                reconciliation.uncertain(), TupleMapper.inactiveMemberTuples(directory));

        Mono<Void> 현재상태 = reconciliation.revokedOnly()
                ? Mono.empty()
                : Mono.defer(() -> state.replaceWith(directory, 기록.directoryListener())).then();
        return snapshots.save(snapshot)
                .then(현재상태)
                .thenReturn(Mono.just(결론(reconciliation, snapshot.id()).withGuardHold(hold)));
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

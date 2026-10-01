package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * SCIM 인스턴스의 수동 재적재. 관리자가 어긋났다고 판단했을 때 실행한다.
 *
 * <p><b>LDAP 판과 무엇이 다른가.</b> {@link RebuildUseCase} 는 LDAP 을 다시 읽어와 DynamoDB 까지 덮어쓴다. SCIM 은 push 모델이라
 * "전체를 다시 달라"고 말할 상대가 없다 — 그래서 <b>현재상태 자체가 진실</b>이고, {@link ScimRebuildMode#TUPLES} 는 그 상태가
 * 요구하는 튜플에 장부를 맞춘다.
 *
 * <p><b>장부를 버리지 않는다(설계 2026-09-29 §3).</b> 있어야 할 줄을 먼저 다 읽고, 전부 쓴 뒤, 장부를 훑어 없어야 할 줄만 지운다
 * ({@link TupleReconciler}). 읽기가 실패하면 장부에 아무것도 하지 않는다. 장부 번호(storeId)는 바뀌지 않는다.
 *
 * <p><b>{@code WIPE} 는 장부를 비운 뒤에만 조직도를 지운다.</b> 청소가 한 줄이라도 실패하면 조직도를 건드리지 않고 FAILED 다 —
 * 조직도가 남아 있어야 다시 실행할 수 있다. 순서를 뒤집으면 조직도가 사라진 채 낡은 권한만 살아남는다.
 *
 * <p><b>요청과 떼어 돈다(설계 2026-09-29 §4, 2026-09-30 §3).</b> {@link #start} 는 {@link SyncJobs#startLocked} 로 작업 락을 잡고 실행 기록을 연 뒤
 * 곧바로 돌아온다. 재적재가 도는 동안 리스를 갱신하고, 리스를 잃으면 멈춰 FAILED 로 남긴다. 끝나면 락을 반납한 뒤 결과를 기록한다.
 *
 * <p><b>감사 이력은 지우지 않는다.</b> {@code WIPE} 도 스냅샷과 실행 이력은 남긴다. 사고 뒤에 "무슨 일이 있었나"를 볼 유일한 기록인데
 * 그것까지 지우면 조사할 수단이 사라진다.
 */
@Slf4j
@RequiredArgsConstructor
public class ScimRebuildUseCase {

    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final TupleSnapshotRepository snapshots;
    private final SyncJobs jobs;
    private final Clock clock;

    /**
     * 작업 락(REBUILD)을 잡고 실행 기록(RUNNING)을 연 뒤 돌려준다. 재적재는 요청과 떼어 돈다 — 결과는 실행 기록으로 본다. 락 잡기·갱신·리스를
     * 잃으면 멈추기·반납은 {@link SyncJobs#startLocked} 가 한다. 못 잡으면 {@link LockUnavailableException} 이고 아무것도 시작하지 않는다.
     *
     * <p>{@code WIPE} 의 확인값 검증은 호출자(컨트롤러)의 몫이다. 여기까지 왔다는 것은 이미 확인됐다는 뜻이므로 값 자체는 받지 않는다.
     */
    public Mono<SyncRun> start(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);
        return jobs.startLocked(SyncSource.SCIM, triggerFor(mode), MutationLock.LockPurpose.REBUILD,
                Mono.defer(() -> rebuild(mode)), run -> {
                });
    }

    private static SyncTrigger triggerFor(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? SyncTrigger.RESET : SyncTrigger.REBUILD;
    }

    private Mono<SyncOutcome> rebuild(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? wipe() : reloadTuples();
    }

    // ---------- TUPLES ----------

    /** 조직도가 비었는데 장부에 지울 줄이 있으면 지우지 않는다(설계 2026-09-30 §4.3) — 조직도를 잃은 채로 돌면 장부 전체가 지워진다. */
    static final String 빈_조직도 = "조직도가 비어 있다 — 장부를 비우려면 mode=wipe";

    private static final TupleReconciler.DeleteCheck 빈_조직도면_멈춘다 = (desired, stale, scanned) ->
            desired.isEmpty() && !stale.isEmpty() ? Optional.of(빈_조직도) : Optional.empty();

    private Mono<SyncOutcome> reloadTuples() {
        return state.loadAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 빈_조직도면_멈춘다)
                    .flatMap(reconciliation -> reconciliation.held()
                            ? Mono.just(SyncOutcome.failed(reconciliation.heldReason()))
                            : commitTuples(reconciliation));
        });
    }

    /**
     * 스냅샷에는 <b>장부에 실제로 있다고 볼 줄</b>만 담는다(설계 §3.1 4단계). 의도한 것을 담으면 부분 실패 뒤 스냅샷이 장부보다 앞서게
     * 되고, 그 기록을 믿는 다음 판단이 전부 어긋난다. 지우기가 차단기로 멈췄어도 같다 — 스냅샷을 남긴 뒤 FAILED 로 기록한다(설계 §5).
     */
    private Mono<SyncOutcome> commitTuples(TupleReconciler.Reconciliation reconciliation) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.SCIM), now, SyncSource.SCIM, reconciliation.ledger());

        return snapshots.save(snapshot)
                .thenReturn(reconciliation.outcome(snapshot.id()));
    }

    // ---------- WIPE ----------

    /**
     * 있어야 할 줄이 없다(D = ∅)고 보고 장부를 비운 <b>뒤</b> 조직도를 지운다. 빈 스냅샷으로 교체하면 현재상태의 직원·조직이 전부 지워진다.
     */
    private Mono<SyncOutcome> wipe() {
        return TupleReconciler.reconcile(writer, scanner, Set.of()).flatMap(reconciliation -> {
            TupleWriteResult result = reconciliation.result();
            if (result.hasFailure()) {
                String 멈춘_이유 = reconciliation.stopReason() == null ? "" : " — 멈춘 이유: " + reconciliation.stopReason();
                return Mono.error(new IllegalStateException(
                        "장부에서 %d줄을 지우지 못해 조직도를 지우지 않았다. 다시 실행하면 남은 줄부터 지운다%s"
                                .formatted(result.failures().size(), 멈춘_이유)));
            }
            return state.replaceWith(DirectorySnapshot.empty())
                    .thenReturn(SyncOutcome.succeeded(result, null))
                    .doOnSuccess(outcome -> log.warn(
                            "SCIM 조직도를 전부 비웠다. IdP 콘솔에서 전체 재프로비저닝을 실행해야 복구된다"));
        });
    }
}

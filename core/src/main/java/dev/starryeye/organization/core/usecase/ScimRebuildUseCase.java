package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
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
import dev.starryeye.organization.core.tuple.TupleSubjects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><b>보류 목록도 맞춘다(설계 2026-10-03 §4.6).</b> 장부를 맞춘 뒤 보류 목록을 순환이라 버린 연결로 바꾸고, {@code WIPE} 는 조직도와 함께 비운다.
 *
 * <p><b>GSI 열거를 장부로 보강한다(설계 2026-10-13 §4).</b> 직원·조직 아이디는 GSI 로 훑는다(최종 일관성). 락 직전에 커밋된 쓰기가 아직 GSI 에 없으면
 * 그 대상의 권한이 "있어야 할 줄에 없는 줄" 로 지워진다. 그래서 지우기 전에, 지울 후보가 가리키는 대상 중 열거에서 빠진 것을 본 테이블에서 강한 일관성으로
 * 다시 읽어 조직도에 넣는다. {@code WIPE} 는 지운 튜플의 대상 중 남은 것을 같은 방법으로 찾아 지운다. 빠진 것이 없으면 읽기가 없다.
 *
 * <p><b>{@code WIPE} 는 장부를 비운 뒤에만 조직도를 지운다.</b> 청소가 한 줄이라도 실패하면 조직도를 건드리지 않고 FAILED 다 —
 * 조직도가 남아 있어야 다시 실행할 수 있다. 순서를 뒤집으면 조직도가 사라진 채 낡은 권한만 살아남는다.
 *
 * <p><b>요청과 떼어 돈다(설계 2026-09-29 §4, 2026-09-30 §3).</b> {@link #start} 는 {@link SyncJobs#startLockedThen} 으로 작업 락을 잡고 실행 기록을
 * 연 뒤 곧바로 돌아온다. 재적재가 도는 동안 리스를 갱신하고, 리스를 잃으면 멈춰 FAILED 로 남긴다. 순서는 일 → 반납 → 튜플 스냅샷 저장 → 이력 → 기록이다 —
 * 스냅샷 저장과 이력은 락을 반납한 뒤에 해 그동안 SCIM 쓰기를 막지 않는다(설계 2026-10-02 §5, 점검 P8, 설계 2026-10-10 §4.4).
 *
 * <p><b>이력(설계 2026-10-10 §4.2).</b> 회차 id 로 남긴다. {@code TUPLES} 는 지운 줄만 — 쓴 줄은 중복 무시로 써 이미 있던 줄과 가를 수 없다.
 * {@code WIPE} 는 지운 직원·조직과 지운 줄 전부다.
 *
 * <p><b>감사 이력은 지우지 않는다.</b> {@code WIPE} 도 스냅샷, 실행 이력, 변경 이력은 남긴다. 사고 뒤에 "무슨 일이 있었나"를 볼 유일한 기록인데
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
    private final AuditRecorder audit;

    /** 이력을 남기지 않는다 — 이력과 무관한 테스트가 쓴다. 운영 결선은 이력을 받는 생성자다. */
    public ScimRebuildUseCase(DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                              TupleSnapshotRepository snapshots, SyncJobs jobs, Clock clock) {
        this(state, writer, scanner, snapshots, jobs, clock, AuditRecorder.NONE);
    }

    /**
     * 작업 락(REBUILD)을 잡고 실행 기록(RUNNING)을 연 뒤 돌려준다. 재적재는 요청과 떼어 돈다 — 결과는 실행 기록으로 본다. 락 잡기·갱신·리스를
     * 잃으면 멈추기·반납과 반납 뒤의 스냅샷 저장·이력은 {@link SyncJobs#startLockedThen} 이 한다. 못 잡으면 {@link LockUnavailableException} 이고
     * 아무것도 시작하지 않는다.
     *
     * <p>{@code WIPE} 의 확인값 검증은 호출자(컨트롤러)의 몫이다. 여기까지 왔다는 것은 이미 확인됐다는 뜻이므로 값 자체는 받지 않는다.
     */
    public Mono<SyncRun> start(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);
        return jobs.startLockedThen(SyncSource.SCIM, triggerFor(mode), MutationLock.LockPurpose.REBUILD,
                run -> Mono.defer(() -> rebuild(mode, AuditCause.run(SyncSource.SCIM, AuditKind.REBUILD, run.runId()))),
                run -> {
                });
    }

    private static SyncTrigger triggerFor(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? SyncTrigger.RESET : SyncTrigger.REBUILD;
    }

    /** 락 안에서 할 일을 하고 "반납 뒤 할 일"을 내놓는다 — 튜플 스냅샷 저장과 이력은 락을 반납한 뒤에 한다. */
    private Mono<Mono<SyncOutcome>> rebuild(ScimRebuildMode mode, AuditCause cause) {
        return mode == ScimRebuildMode.WIPE ? wipe(cause) : reloadTuples(cause);
    }

    // ---------- TUPLES ----------

    /** 조직도가 비었는데 장부에 지울 줄이 있으면 지우지 않는다(설계 2026-09-30 §4.3) — 조직도를 잃은 채로 돌면 장부 전체가 지워진다. */
    static final String 빈_조직도 = "조직도가 비어 있다 — 장부를 비우려면 mode=wipe";

    private static final TupleReconciler.DeleteCheck 빈_조직도면_멈춘다 = (desired, stale, scanned) ->
            desired.isEmpty() && !stale.isEmpty() ? Optional.of(빈_조직도) : Optional.empty();

    /** 열거에서 빠진 조직을 다시 읽을 때의 동시성. 평소에는 0개다. */
    private static final int 보강_동시성 = 8;

    /**
     * 장부를 맞춘 뒤 보류 목록을 버린 연결로 바꾼다 — 락 안이다. 멈춘 경우(빈 조직도)는 목록을 두고 간다. 지우기 전에 GSI 열거를 장부로 보강하고
     * (설계 2026-10-13 §4.3), 있어야 할 줄(D')과 보류 목록이 함께 보강한 조직도를 따른다 — 보강한 조직도가 순환이라 버린 연결은 장부에서도 지운다.
     */
    private Mono<Mono<SyncOutcome>> reloadTuples(AuditCause cause) {
        return state.loadAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
            // 보강한 조직도의 결과 — 있어야 할 줄(D')과 보류 목록이 함께 이것을 따른다. 보강할 것이 없으면 처음 결과 그대로다
            AtomicReference<TupleMappingResult> 최종 = new AtomicReference<>(mapping);
            TupleReconciler.DesiredRevision 보강 = (desired, stale) -> 열거를_보강한다(directory, stale).map(보강된 -> {
                if (보강된 == directory) {
                    return desired;
                }
                TupleMappingResult 다시 = TupleMapper.toTuples(보강된);
                최종.set(다시);
                return 다시.tuples();
            });

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 보강, 빈_조직도면_멈춘다)
                    .flatMap(reconciliation -> reconciliation.held()
                            ? Mono.just(Mono.just(SyncOutcome.failed(reconciliation.heldReason())))
                            // 재적재는 전체를 정하는 연산이라 보류 목록도 처음부터 다시 정한다(설계 2026-10-03 §4.6).
                            // 이력의 시각은 락 안의 일이 끝난 이때 잡는다 — 쓰는 것은 반납한 뒤다(설계 2026-10-10 §4.4)
                            : state.replaceCutEdges(최종.get().cutEdges())
                                    .then(Mono.fromSupplier(() -> commitTuples(reconciliation, cause, audit.now()))));
        });
    }

    /**
     * 지울 후보가 가리키는 대상 중 GSI 열거에서 빠진 것을 본 테이블에서 강한 일관성으로 다시 읽어 조직도에 넣는다(설계 2026-10-13 §4.3).
     * 다시 읽은 조직의 멤버 중 조직도에 없는 직원도 함께 읽는다 — 장부에 아직 줄이 없는 새 멤버다. 빠진 것이 없거나 다시 읽어도 없으면 받은 조직도를
     * 그대로 준다(같은 객체) — 평소에는 읽기가 없다.
     */
    private Mono<DirectorySnapshot> 열거를_보강한다(DirectorySnapshot directory, Set<RelationTuple> stale) {
        Set<String> 빠진_직원 = new LinkedHashSet<>();
        Set<String> 빠진_조직 = new LinkedHashSet<>();
        for (MemberRef subject : TupleSubjects.of(stale)) {
            if (subject.type() == MemberType.USER && !directory.users().containsKey(subject.id())) {
                빠진_직원.add(subject.id());
            } else if (subject.type() == MemberType.GROUP && !directory.groups().containsKey(subject.id())) {
                빠진_조직.add(subject.id());
            }
        }
        if (빠진_직원.isEmpty() && 빠진_조직.isEmpty()) {
            return Mono.just(directory);
        }
        return Flux.fromIterable(빠진_조직)
                .flatMap(state::findGroup, 보강_동시성)
                .collectMap(DirectoryGroup::id)
                .flatMap(groups -> {
                    Set<String> 읽을_직원 = new LinkedHashSet<>(빠진_직원);
                    groups.values().forEach(group -> group.members().stream()
                            .filter(member -> member.type() == MemberType.USER
                                    && !directory.users().containsKey(member.id()))
                            .forEach(member -> 읽을_직원.add(member.id())));
                    Mono<Map<String, DirectoryUser>> users = 읽을_직원.isEmpty()
                            ? Mono.just(Map.of())
                            : state.findUsers(읽을_직원).collectMap(DirectoryUser::id);
                    return users.map(found -> {
                        if (groups.isEmpty() && found.isEmpty()) {
                            return directory;
                        }
                        log.warn("GSI 열거에서 빠진 대상을 본 테이블에서 찾아 조직도에 넣었다 — 직원 {}명, 조직 {}개. 락 직전에 커밋된 쓰기다",
                                found.size(), groups.size());
                        Map<String, DirectoryUser> 직원들 = new LinkedHashMap<>(directory.users());
                        직원들.putAll(found);
                        Map<String, DirectoryGroup> 조직들 = new LinkedHashMap<>(directory.groups());
                        조직들.putAll(groups);
                        return new DirectorySnapshot(직원들, 조직들);
                    });
                });
    }

    /**
     * 스냅샷에는 <b>장부에 실제로 있다고 볼 줄</b>만 담는다(설계 §3.1 4단계). 지우기가 차단기로 멈췄어도 같다 — 스냅샷을 남긴 뒤 FAILED 로 기록한다.
     * <b>락을 반납한 뒤에 돈다</b>(설계 2026-10-02 §5) — SCIM 쓰기 경로는 이 스냅샷을 읽지 않는다. 저장이 실패하면 FAILED 다 — 장부는 이미 맞췄다.
     * 저장한 뒤 지운 줄을 이력에 남긴다(설계 2026-10-10 §4.2). 이력의 시각 {@code at} 은 락 안의 일이 끝났을 때 잡은 것이다(§4.4).
     */
    private Mono<SyncOutcome> commitTuples(TupleReconciler.Reconciliation reconciliation, AuditCause cause, Instant at) {
        return Mono.defer(() -> {
                    Instant now = clock.instant();
                    TupleSnapshot snapshot = new TupleSnapshot(
                            SnapshotIds.generate(now, SyncSource.SCIM), now, SyncSource.SCIM, reconciliation.ledger());
                    return snapshots.save(snapshot).thenReturn(reconciliation.outcome(snapshot.id()));
                })
                .onErrorMap(error -> new IllegalStateException("장부는 맞췄다 — 스냅샷 저장 실패: "
                        + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()), error))
                .flatMap(outcome -> audit.recordRun(cause, at,
                        new AuditChanges().revoked(reconciliation.result().deleted()).entries(), outcome));
    }

    // ---------- WIPE ----------

    /**
     * 있어야 할 줄이 없다(D = ∅)고 보고 장부를 비운 <b>뒤</b> 조직도를 지운다. 빈 스냅샷으로 교체하면 현재상태의 직원·조직이 전부 지워진다.
     * 이력(지운 직원·조직과 지운 줄)은 락을 반납한 뒤에 남긴다.
     */
    private Mono<Mono<SyncOutcome>> wipe(AuditCause cause) {
        return TupleReconciler.reconcile(writer, scanner, Set.of()).flatMap(reconciliation -> {
            TupleWriteResult result = reconciliation.result();
            if (result.hasFailure()) {
                String 멈춘_이유 = reconciliation.stopReason() == null ? "" : " — 멈춘 이유: " + reconciliation.stopReason();
                return Mono.error(new IllegalStateException(
                        "장부에서 %d줄을 지우지 못해 조직도를 지우지 않았다. 다시 실행하면 남은 줄부터 지운다%s"
                                .formatted(result.failures().size(), 멈춘_이유)));
            }
            // 조직도와 함께 보류 목록도 비운다 — 연결의 멤버 줄이 사라졌으니 보류할 것도 없다
            return state.replaceWith(DirectorySnapshot.empty())
                    .flatMap(changes -> state.replaceCutEdges(Set.of()).thenReturn(changes))
                    .doOnSuccess(changes -> log.warn(
                            "SCIM 조직도를 전부 비웠다. IdP 콘솔에서 전체 재프로비저닝을 실행해야 복구된다"))
                    .map(changes -> {
                        // 시각은 락 안에서 조직도를 지운 직후에 잡는다 — 쓰는 것은 반납한 뒤다(설계 2026-10-10 §4.4)
                        Instant at = audit.now();
                        return Mono.defer(() -> audit.recordRun(cause, at,
                                new AuditChanges().directory(changes).revoked(result.deleted()).entries(),
                                SyncOutcome.succeeded(result, null)));
                    });
        });
    }
}

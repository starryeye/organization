package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.model.UniqueKind;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 * 다시 읽어 조직도에 넣는다. {@code WIPE} 는 지운 튜플의 대상과 유일 값 표지판의 주인(강한 일관성 열거, 설계 2026-10-14 §5) 중 남은 것을 같은 방법으로 찾아 지운다. 빠진 것이 없으면 읽기가 없다.
 *
 * <p><b>{@code WIPE} 는 장부를 비운 뒤에만 조직도를 지운다.</b> 청소가 한 줄이라도 실패하면 조직도를 건드리지 않고 FAILED 다 —
 * 조직도가 남아 있어야 다시 실행할 수 있다. 순서를 뒤집으면 조직도가 사라진 채 낡은 권한만 살아남는다.
 *
 * <p><b>요청과 떼어 돈다(설계 2026-09-29 §4, 2026-09-30 §3).</b> {@link #start} 는 {@link SyncJobs#startLockedThen} 으로 작업 락을 잡고 실행 기록을
 * 연 뒤 곧바로 돌아온다. 재적재가 도는 동안 리스를 갱신하고, 리스를 잃으면 멈춰 FAILED 로 남긴다. 순서는 일 → 반납 → 튜플 스냅샷 저장 → 이력 → 기록이다 —
 * 스냅샷 저장과 이력은 락을 반납한 뒤에 해 그동안 SCIM 쓰기를 막지 않는다(설계 2026-10-02 §5, 점검 P8, 설계 2026-10-10 §4.4).
 *
 * <p><b>이력(설계 2026-10-10 §4.2).</b> 회차 id 로 남긴다. {@code TUPLES} 는 지운 줄만 — 쓴 줄은 중복 무시로 써 이미 있던 줄과 가를 수 없다.
 * {@code WIPE} 는 지운 직원·조직과 지운 줄 전부다. 지운 줄과 지운 직원·조직은 끝나는 즉시 회차의 변경 모음에 더하고, 회차가 어떻게 끝나든 {@link SyncJobs} 가
 * 락을 반납한 뒤 남긴다 — 도중에 실패한 재적재도 그때까지 지운 것이 남는다(설계 2026-10-15).
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

    /**
     * 작업 락(REBUILD)을 잡고 실행 기록(RUNNING)을 연 뒤 돌려준다. 재적재는 요청과 떼어 돈다 — 결과는 실행 기록으로 본다. 락 잡기·갱신·리스를
     * 잃으면 멈추기·반납과 반납 뒤의 스냅샷 저장·이력은 {@link SyncJobs#startLockedThen} 이 한다. 못 잡으면 {@link LockUnavailableException} 이고
     * 아무것도 시작하지 않는다.
     *
     * <p>{@code WIPE} 의 확인값 검증은 호출자(컨트롤러)의 몫이다. 여기까지 왔다는 것은 이미 확인됐다는 뜻이므로 값 자체는 받지 않는다.
     */
    public Mono<SyncRun> start(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);
        return jobs.startLockedThen(SyncSource.SCIM, triggerFor(mode), MutationLock.LockPurpose.REBUILD, AuditKind.REBUILD,
                (run, 기록) -> Mono.defer(() -> rebuild(mode, 기록.changes())),
                run -> {
                });
    }

    private static SyncTrigger triggerFor(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? SyncTrigger.RESET : SyncTrigger.REBUILD;
    }

    /** 락 안에서 할 일을 하고 "반납 뒤 할 일"을 내놓는다 — 튜플 스냅샷 저장은 락을 반납한 뒤에 한다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다. */
    private Mono<Mono<SyncOutcome>> rebuild(ScimRebuildMode mode, AuditChanges 기록) {
        return mode == ScimRebuildMode.WIPE ? wipe(기록) : reloadTuples(기록);
    }

    // ---------- TUPLES ----------

    /** 조직도가 비었는데 장부에 지울 줄이 있으면 지우지 않는다(설계 2026-09-30 §4.3) — 조직도를 잃은 채로 돌면 장부 전체가 지워진다. */
    static final String 빈_조직도 = "조직도가 비어 있다 — 장부를 비우려면 mode=wipe";

    private static final TupleReconciler.DeleteCheck 빈_조직도면_멈춘다 = (desired, stale, scanned) ->
            desired.isEmpty() && !stale.isEmpty()
                    ? TupleReconciler.DeleteVerdict.holdAll(빈_조직도)
                    : TupleReconciler.DeleteVerdict.proceed(stale);

    /** 열거에서 빠진 조직을 다시 읽을 때의 동시성. 평소에는 0개다. */
    private static final int 보강_동시성 = 8;

    /**
     * 장부를 맞춘 뒤 보류 목록을 버린 연결로 바꾼다 — 락 안이다. 멈춘 경우(빈 조직도)는 목록을 두고 간다. 지우기 전에 GSI 열거를 장부로 보강하고
     * (설계 2026-10-13 §4.3), 있어야 할 줄(D')과 보류 목록이 함께 보강한 조직도를 따른다 — 보강한 조직도가 순환이라 버린 연결은 장부에서도 지운다.
     */
    private Mono<Mono<SyncOutcome>> reloadTuples(AuditChanges 기록) {
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
                // 처음 변환과 같이 남기되 앞에 붙여 두 번을 가른다 — 보강으로 새로 끊긴 순환 연결은 여기서만 보인다
                다시.warnings().forEach(warning -> log.warn("튜플 변환 경고: 다시 계산한 조직도 — {}", warning));
                최종.set(다시);
                return 다시.tuples();
            });

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 보강, 빈_조직도면_멈춘다, 기록::revoked)
                    .flatMap(reconciliation -> reconciliation.heldEntirely()
                            ? Mono.just(Mono.just(SyncOutcome.failed(reconciliation.heldReason())))
                            // 재적재는 전체를 정하는 연산이라 보류 목록도 처음부터 다시 정한다(설계 2026-10-03 §4.6)
                            : state.replaceCutEdges(최종.get().cutEdges())
                                    .then(Mono.fromSupplier(() -> commitTuples(reconciliation))));
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
     * 스냅샷에는 <b>장부에 있다고 확실히 아는 줄</b>(B)과 <b>결과를 몰라 있는지 모르는 줄</b>(U)을 따로 담는다(설계 §3.1 4단계, 2026-10-20 §3.4). 지우기가 차단기로 멈췄어도 같다 — 스냅샷을 남긴 뒤 FAILED 로 기록한다.
     * <b>락을 반납한 뒤에 돈다</b>(설계 2026-10-02 §5) — SCIM 쓰기 경로는 이 스냅샷을 읽지 않는다. 저장이 실패하면 FAILED 다 — 장부는 이미 맞췄다.
     * 지운 줄은 지우기 묶음이 끝날 때마다 이미 회차의 변경 모음에 들어 있어, 저장이 실패해도 이력에 남는다(설계 2026-10-15 §5).
     */
    private Mono<SyncOutcome> commitTuples(TupleReconciler.Reconciliation reconciliation) {
        return Mono.defer(() -> {
                    Instant now = clock.instant();
                    TupleSnapshot snapshot = new TupleSnapshot(
                            SnapshotIds.generate(now, SyncSource.SCIM), now, SyncSource.SCIM, reconciliation.ledger(),
                            reconciliation.uncertain());
                    return snapshots.save(snapshot).thenReturn(reconciliation.outcome(snapshot.id()));
                })
                .onErrorMap(error -> new IllegalStateException("장부는 맞췄다 — 스냅샷 저장 실패: "
                        + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()), error));
    }

    // ---------- WIPE ----------

    /**
     * 있어야 할 줄이 없다(D = ∅)고 보고 장부를 비운 <b>뒤</b> 조직도를 지운다. 빈 스냅샷으로 교체하면 현재상태의 직원·조직이 전부 지워진다.
     * 지운 줄은 지우기 묶음마다, 지운 직원·조직은 하나마다 끝나는 즉시 {@code 기록} 에 더한다 — 어디서 실패해도 그때까지 지운 것이 이력에 남는다(설계 2026-10-15 §5).
     */
    private Mono<Mono<SyncOutcome>> wipe(AuditChanges 기록) {
        DirectoryChanges.Listener 변경마다 = 기록.directoryListener();
        return TupleReconciler.reconcile(writer, scanner, Set.of(), TupleReconciler.DesiredRevision.NONE,
                        TupleReconciler.DeleteCheck.NONE, 기록::revoked)
                .flatMap(reconciliation -> {
                    TupleWriteResult result = reconciliation.result();
                    if (result.hasFailure()) {
                        String 멈춘_이유 = reconciliation.stopReason() == null ? "" : " — 멈춘 이유: " + reconciliation.stopReason();
                        return Mono.error(new IllegalStateException(
                                "장부에서 %d줄을 지우지 못해 조직도를 지우지 않았다. 다시 실행하면 남은 줄부터 지운다%s"
                                        .formatted(result.failures().size(), 멈춘_이유)));
                    }
                    // 조직도와 함께 보류 목록도 비운다 — 연결의 멤버 줄이 사라졌으니 보류할 것도 없다
                    return state.replaceWith(DirectorySnapshot.empty(), 변경마다)
                            // GSI 열거에서 빠져 남은 대상을 장부와 표지판의 주인으로 찾아 지운다(설계 2026-10-13 §4.4, 2026-10-14 §5)
                            .flatMap(changes -> 남은_대상을_지운다(changes, result.deleted(), 변경마다))
                            // 표지판도 비운다 — 조직도가 비었으니 쥘 값도 없다(설계 2026-10-14 §5)
                            .then(Mono.defer(state::clearUniqueMarkers))
                            .then(Mono.defer(() -> state.replaceCutEdges(Set.of())))
                            .doOnSuccess(ignored -> log.warn(
                                    "SCIM 조직도를 전부 비웠다. IdP 콘솔에서 전체 재프로비저닝을 실행해야 복구된다"))
                            .thenReturn(Mono.just(SyncOutcome.succeeded(result, null)));
                });
    }

    /**
     * 조직도를 비운 뒤 남은 것을 지운다(설계 2026-10-13 §4.4). 지운 튜플의 대상과 <b>표지판의 주인</b> 중 방금 지운 직원·조직에 없는 것을 본 테이블에서
     * 강한 일관성으로 다시 확인해, 아직 있으면 조직 먼저(조직 전부를 읽은 뒤 읽은 멤버로), 그다음 직원 순으로 지운다 — GSI 열거에서 빠진, 락 직전에 만든 대상이다.
     * 표지판은 강한 일관성 열거라(설계 2026-10-14 §5) 어느 조직에도 없어 장부에 줄이 없는, 방금 만든 직원까지 잡는다 — 표지판을 비우기 전에 지워야
     * IdP 가 같은 userName 으로 다시 POST 해도 중복이 생기지 않는다. 지운 것은 하나 지울 때마다 {@code 변경마다} 로 알려 이력에 삭제로 남긴다(설계 2026-10-15 §4).
     * 확인할 것이 없으면 아무것도 하지 않는다 — 평소에는 표지판 Query 둘뿐이고 다시 읽기는 없다(주인이 모두 방금 지운 것이다).
     *
     * <p><b>"방금 지운" 은 비교 결과에서 이후가 없는 것(삭제)만이다.</b> 보이는 직원을 지우며 멤버 줄이 빠진 조직은 이름표가 그대로인 "멤버만 빠짐"
     * 비교 결과로 오는데, 그 조직이 열거에서 빠진 것이면 남아 있다 — 지워진 것으로 세면 다시 읽지 않아 조직이 남는다. 그 조직의 삭제는 지울 때 읽은 멤버만
     * 나간 멤버로 알린다. 먼저 빠진 멤버는 "멤버만 빠짐" 으로 이미 알렸고, 변경 모음이 둘을 합친다 — 이름표가 그대로인 결과는 상태를 보지 않으므로
     * 삭제 줄의 이름표도 남는다(설계 2026-10-15 §5).
     */
    private Mono<Void> 남은_대상을_지운다(DirectoryChanges changes, Set<RelationTuple> deleted,
                                                DirectoryChanges.Listener 변경마다) {
        // 표지판의 주인은 강한 일관성으로 읽는다 — 읽기가 실패하면 표지판을 비우지 않고 FAILED 다(조직도가 남아 다시 실행할 수 있다)
        return Mono.zip(state.findUniqueOwners(UniqueKind.USER_NAME).collect(Collectors.toCollection(LinkedHashSet::new)),
                        state.findUniqueOwners(UniqueKind.GROUP_EXTERNAL_ID).collect(Collectors.toCollection(LinkedHashSet::new)))
                .flatMap(owners -> 남은_대상을_지운다(changes, deleted, owners.getT1(), owners.getT2(), 변경마다));
    }

    private Mono<Void> 남은_대상을_지운다(DirectoryChanges changes, Set<RelationTuple> deleted,
                                                Set<String> 표지판_직원, Set<String> 표지판_조직,
                                                DirectoryChanges.Listener 변경마다) {
        Set<String> 지운_직원 = changes.users().stream()
                .filter(diff -> diff.after() == null)
                .map(DirectoryChanges.UserDiff::id)
                .collect(Collectors.toSet());
        Set<String> 지운_조직 = changes.groups().stream()
                .filter(diff -> diff.after() == null)
                .map(DirectoryChanges.GroupDiff::id)
                .collect(Collectors.toSet());
        Set<String> 볼_직원 = new LinkedHashSet<>();
        Set<String> 볼_조직 = new LinkedHashSet<>();
        for (MemberRef subject : TupleSubjects.of(deleted)) {
            if (subject.type() == MemberType.USER && !지운_직원.contains(subject.id())) {
                볼_직원.add(subject.id());
            } else if (subject.type() == MemberType.GROUP && !지운_조직.contains(subject.id())) {
                볼_조직.add(subject.id());
            }
        }
        // 표지판의 주인 중 replaceWith 가 지우지 않은 것 — 같은 주인이 여러 번 나올 수 있다
        표지판_직원.stream().filter(id -> !지운_직원.contains(id)).forEach(볼_직원::add);
        표지판_조직.stream().filter(id -> !지운_조직.contains(id)).forEach(볼_조직::add);
        if (볼_직원.isEmpty() && 볼_조직.isEmpty()) {
            return Mono.empty();
        }
        // 두 단계다 — 지울 조직 전부의 멤버를 먼저 읽고, 그다음 읽은 멤버로 지운다(replaceWith 와 같다). 하위 조직을 먼저 지우면 실제 저장소는 상위 조직의
        // 멤버 줄에서도 그 하위 조직을 지워, 뒤에 읽는 상위 조직은 하위 조직을 멤버로 모른다 — 이력의 나간 멤버에서 빠진다. 이미 지워진 하위 조직이 멤버로 적힌
        // 채 지워도 된다 — DeleteItem 은 없는 키에도 성공한다
        Mono<Long> 지운_조직_수 = Flux.fromIterable(볼_조직)
                .flatMap(state::findGroup, 보강_동시성)
                .collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(group -> state.deleteGroup(group.id(), group.members())
                        .then(Mono.fromRunnable(() -> 변경마다.group(new DirectoryChanges.GroupDiff(
                                new GroupHeader(group.id(), group.externalId(), group.displayName()), null, Set.of(),
                                group.members()))))
                        .thenReturn(group.id()))
                .count();
        Mono<Long> 지운_직원_수 = Mono.defer(() -> 볼_직원.isEmpty()
                ? Mono.just(0L)
                : state.findUsers(볼_직원)
                        .concatMap(user -> state.deleteUser(user.id())
                                .then(Mono.fromRunnable(() -> 변경마다.user(new DirectoryChanges.UserDiff(user, null))))
                                .thenReturn(user.id()))
                        .count());
        return 지운_조직_수.flatMap(groups -> 지운_직원_수.doOnNext(users -> {
            if (groups > 0 || users > 0) {
                log.warn("GSI 열거에서 빠져 남아 있던 대상을 지웠다 — 직원 {}명, 조직 {}개. 락 직전에 만든 대상이다", users, groups);
            }
        })).then();
    }
}

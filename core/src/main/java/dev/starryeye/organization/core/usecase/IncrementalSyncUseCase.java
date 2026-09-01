package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * SCIM 이 보낸 단건 변경을 튜플에 반영한다.
 *
 * <p>LDAP 은 전체를 읽어 직전 스냅샷과 diff 하지만, SCIM 은 리소스 하나의 변경만 온다.
 * 그래서 <b>영향 범위만 담은 최소 스냅샷</b>을 변경 전후로 각각 만들어 {@link TupleMapper} 에
 * 통과시키고, 그 둘을 {@link TupleDiff} 로 비교한다. 튜플 생성 규칙(비활성 유저 제외,
 * 없는 멤버 스킵)이 한 곳에만 있게 하려는 것이다 — 손으로 계산하면 규칙이 두 벌이 되고
 * 언젠가 어긋난다.
 *
 * <p>영향 범위:
 * <ul>
 *   <li>조직 변경 — 그 조직 + <b>그 조직을 하위 조직으로 갖는 상위 조직들</b>(멤버 목록까지
 *       그대로. {@link #upsertGroup} 참고) + 그 조직들의 멤버 유저들(활성 여부 판정에 필요) +
 *       멤버로 참조된 하위 조직의 <b>존재</b>(존재 확인에 필요, {@link TupleMapper} 가 child
 *       엣지를 만들려면 그 하위 조직이 스냅샷에 있어야 한다 — 단, 그 하위 조직 자신의 멤버까지
 *       실으면 안 된다. {@link #expandWithReferencedGroups} 참고)</li>
 *   <li>유저 변경 — 그 유저 + 그 유저가 속한 모든 조직({@code findGroupIdsContaining} 으로
 *       찾음). {@code active} 가 뒤집히면 그 유저의 모든 {@code direct_member} 튜플이
 *       생기거나 사라진다</li>
 * </ul>
 *
 * <p><b>최소 스냅샷이 볼 수 있는 규칙과 볼 수 없는 규칙(설계의 경계).</b>
 * "튜플 규칙은 {@link TupleMapper} 한 곳에만 있다"는 명제는 <i>멤버 단위</i> 규칙에 대해서만
 * 무조건 참이다. 최소 스냅샷은 그래프 전체를 싣지 않으므로 <i>그래프 전역</i> 규칙은 그대로는
 * 성립하지 않는다.
 * <ul>
 *   <li><b>스냅샷이 볼 수 있는 것(멤버 단위 규칙, {@link TupleMapper} 가 그대로 담당).</b>
 *       비활성 유저는 튜플을 만들지 않는다 / 스냅샷에 없는 유저·조직은 경고하고 건너뛴다 /
 *       조직명은 튜플에 넣지 않는다. 이 규칙들은 한 조직과 그 직속 멤버만 보면 판정되고,
 *       최소 스냅샷은 언제나 그만큼은 싣는다.</li>
 *   <li><b>스냅샷이 볼 수 없는 것 1 — child 엣지의 존재 조건.</b> 엣지 {@code (child, parent)}
 *       는 부모 쪽 멤버 목록에서 나오므로, 자식만 실은 스냅샷에는 아예 나타나지 않는다.
 *       → {@link #upsertGroup} 이 {@link #parentsOf} 로 <b>상위 조직들을 멤버 목록째로</b>
 *       both 스냅샷에 싣는 것으로 해결한다. 그래야 "부모가 먼저 참조해 둔 자식이 나중에 도착"
 *       하는 순서에서도 엣지가 만들어진다. 이때 <b>없던 조직은 before 스냅샷에서 완전히
 *       빼야</b> 한다 — 멤버 0개짜리 대역을 넣으면 before 에도 엣지가 생겨 델타가 비어버린다.</li>
 *   <li><b>스냅샷이 볼 수 없는 것 2 — 비순환 보장(설계 §5.3).</b>
 *       {@link TupleMapper#toTuples} 의 DFS 는 스냅샷 안의 그래프만 훑는다. 참조로 딸려온
 *       하위 조직은 일부러 멤버를 비워 싣기 때문에({@link #expandWithReferencedGroups})
 *       두 홉 이상 떨어진 순환은 최소 스냅샷에서 보이지 않는다.
 *       → 새로 생기는 child 엣지마다 {@link #reaches} 로 저장소를 타고 자손을 훑어
 *       도달성을 직접 확인하고, 순환을 닫는 엣지는 {@link TupleMapper} 와 같은 문구로 경고하며
 *       버린다({@link #withoutCycleCreatingEdges}). 조상·자손 전체를 스냅샷에 싣는 방법도
 *       있지만 요청 한 건마다 비용이 훨씬 크다.</li>
 * </ul>
 *
 * <p>이 유스케이스는 {@code SyncRun} 을 기록하지 않는다. SCIM 은 요청 단위라 이력이 폭증한다.
 *
 * <p><b>부분 실패(design §7.2).</b> OpenFGA 배치는 트랜잭션이므로 SCIM 단건 변경은 대개
 * 전부 성공이거나 전부 실패다. 대형 그룹 PUT 으로 배치가 쪼개질 때만 부분 성공이 가능한데,
 * 실패한 부분을 그대로 반영된 것처럼 저장하면 OpenFGA 에는 없는데 DynamoDB 에는 있는(또는
 * 그 반대인) 상태가 되고, 다음 동기화의 diff는 "이미 같다"고 판단해 이 불일치를 영원히
 * 다시 잡지 못한다 — 재시도가 diff 할 "이전" 자체가 이미 목표값으로 오염됐기 때문이다.
 * 그래서 모든 커밋은 반드시 {@code TupleWriteResult} 를 보고 실제로 반영된 만큼만 상태에
 * 남긴다({@link #diffAndApply}, {@link #reconcileGroupMembers}, {@link #reconcileRemovedMember}).
 */
@Slf4j
@RequiredArgsConstructor
public class IncrementalSyncUseCase {

    private static final int LOAD_CONCURRENCY = 8;

    /** 요청 하나의 순환 검사가 훑을 수 있는 조직 수 상한. {@link CycleScan} 참고. */
    private static final int MAX_GRAPH_EXPANSIONS = 10_000;

    /** 획득 재시도 간격. 대기 한도를 이 값으로 나눈 횟수가 재시도 횟수다. */
    private static final Duration ACQUIRE_RETRY_DELAY = Duration.ofMillis(200);

    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleChecker checker;
    private final MutationLock lock;
    /**
     * 락 획득을 포기하기까지의 대기 한도 (설계 §4.4). 재시도 <b>횟수</b>가 아니라 한도를
     * 받는다 — 횟수를 받으면 그것을 계산한 쪽이 {@link #ACQUIRE_RETRY_DELAY} 를 따로 알고
     * 있어야 하고, 컴파일러가 이어주지 않는 그 중복 때문에 한쪽만 바뀌면 획득 예산이
     * 조용히 달라진다.
     */
    private final Duration acquireTimeout;
    private final DriftObserver driftObserver;

    private long acquireRetries() {
        return acquireTimeout.toMillis() / ACQUIRE_RETRY_DELAY.toMillis();
    }

    /**
     * 어긋남을 발견했을 때 부른다. 기본은 아무것도 하지 않는다.
     *
     * <p>{@code core} 가 Micrometer 를 알지 않게 하려고 콜백으로 받는다 — 이 모듈의 의존성은
     * reactor 와 slf4j 뿐이고, 그 경계를 지표 때문에 허물지 않는다.
     */
    public interface DriftObserver {
        void observed(int extra, int missing);

        DriftObserver NOOP = (extra, missing) -> {
        };
    }

    /**
     * 직원 생성·수정. 활성 여부가 바뀌면 그 직원이 속한 모든 조직의 튜플이 함께 움직인다.
     *
     * <p>반영이 실패하면 {@code active} 를 요청값 그대로 저장하지 않고 이전 값으로 되돌린다.
     * 그대로 저장하면 다음 동기화가 "이미 목표 상태"라고 오판해 실패한 튜플을 영원히
     * 다시 시도하지 못한다.
     *
     * <p>아직 한 번도 저장된 적 없는 직원은 <b>비활성으로 취급</b>해 "이전"을 구성한다 —
     * 존재하지 않는 직원은 튜플을 만들지 않는다는 점에서 비활성 직원과 같다. 이 자리에
     * 요청값(={@code user}) 자체를 fallback 으로 쓰면 before/after 가 똑같아져 델타가
     * 비어버리고, 조직이 먼저 참조해 둔 신규 직원의 첫 튜플이 영원히 만들어지지 않는다.
     *
     * <p>조직 쪽({@link #upsertGroup})과 달리 여기서는 "없는 직원"과 "멤버가 될 수 없는 직원"을
     * 같은 대역으로 뭉뚱그려도 안전하다 — 비활성 유저는
     * {@link TupleMapper#toTuples} 에서 튜플 기여가 0 이고, 스냅샷에 아예 없는 유저도(경고만
     * 남기고) 기여가 0 이라 두 상태가 튜플 관점에서 구별되지 않기 때문이다. 조직은 그렇지
     * 않다: 멤버 0개인 조직은 <b>상위 조직의 child 엣지</b>를 성립시키지만 없는 조직은 그렇지
     * 않아, 같은 대역을 쓰면 델타가 사라진다.
     */
    public Mono<IncrementalSyncResult> upsertUser(DirectoryUser user) {
        return withLock(lease -> upsertUserInternal(user, lease));
    }

    private Mono<IncrementalSyncResult> upsertUserInternal(DirectoryUser user, LockLease lease) {
        DirectoryUser neverStored = new DirectoryUser(
                user.id(), user.externalId(), user.userName(), user.displayName(), user.email(), false);

        return affectedGroupsOf(user.id())
                .flatMap(groups -> state.findUser(user.id())
                        .defaultIfEmpty(neverStored)
                        .flatMap(existingUser -> {
                            Mono<DirectorySnapshot> before = snapshotOf(groups, Mono.just(existingUser));
                            Mono<DirectorySnapshot> after = snapshotOf(groups, Mono.just(user));

                            Commit commit = (result, beforeTuples, afterTuples) ->
                                    Mono.defer(() -> state.saveUser(reconcileUser(existingUser, user, result)));

                            return diffAndApply(before, after, RelationTuple.userRef(user.id()), lease, commit);
                        }));
    }

    /**
     * 조직 생성·수정. 멤버 목록을 통째로 교체한다.
     *
     * <p><b>상위 조직도 함께 싣는다.</b> child 엣지 {@code (group:자식, child, group:부모)} 는
     * 부모의 멤버 목록에서 나오므로, 이 조직만 실은 스냅샷에는 그 엣지가 아예 등장하지 않는다.
     * 그래서 부모가 이미 이 조직을 멤버로 적어 둔 채 이 조직이 뒤늦게 도착하면
     * ({@link TupleMapper} 가 "스냅샷에 없어 건너뜁니다" 로 미뤄 뒀던 경우) 그 엣지를 영원히
     * 쓰지 못했다. {@link #parentsOf} 로 상위 조직들을 <b>멤버 목록 그대로</b> before/after
     * 양쪽에 실어 기여를 대칭으로 만든다 — 이미 존재하던 조직이면 엣지가 양쪽에 다 있어
     * 델타에 나타나지 않고, 새로 생긴 조직이면 after 에만 있어 정확히 그 엣지만 새로 쓰인다.
     *
     * <p><b>없던 조직은 before 에서 통째로 뺀다.</b> "멤버 0개인 조직이 있다" 와 "조직이 없다"
     * 는 서로 다른 상태다. 없는 조직 자리에 멤버 0개짜리 대역을 넣으면
     * {@link TupleMapper#toTuples} 가 before 에서도 부모의 child 엣지를 만들어 버려 위 델타가
     * 다시 비어버린다. (유저 쪽 {@link #upsertUser} 의 대역은 <b>비활성</b> 유저라 어느 쪽
     * 스냅샷에서도 튜플 기여가 0 이므로 같은 문제가 없다 — 확인함.)
     *
     * <p>부분 실패 시에는 요청된 멤버 목록을 그대로 저장하지 않는다. 실제로 튜플이 반영된
     * 멤버만 추가되고, 실제로 튜플이 지워진 멤버만 제외된다({@link #reconcileGroupMembers}).
     * 다만 <b>아직 없던 조직</b>은 하나라도 실패하면 레코드 자체를 만들지 않는다. 만들어 두면
     * 다음 diff 의 "이전"에 부모의 child 엣지가 이미 포함돼 그 엣지를 영원히 다시 쓰지 못한다
     * ({@link #removeUser} 가 실패 시 직원 레코드를 지우지 않는 것과 같은 이유다).
     */
    public Mono<IncrementalSyncResult> upsertGroup(DirectoryGroup group) {
        return withLock(lease -> upsertGroupInternal(group, lease));
    }

    private Mono<IncrementalSyncResult> upsertGroupInternal(DirectoryGroup group, LockLease lease) {
        return state.findGroup(group.id())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> parentsOf(group.id()).flatMap(parents -> {
                    Set<DirectoryGroup> beforeGroups = new LinkedHashSet<>(parents);
                    existing.ifPresent(beforeGroups::add);
                    Set<DirectoryGroup> afterGroups = new LinkedHashSet<>(parents);
                    afterGroups.add(group);

                    Mono<DirectorySnapshot> before = snapshotOfGroups(beforeGroups);
                    Mono<DirectorySnapshot> after = snapshotOfGroups(afterGroups);

                    DirectoryGroup existingOrEmpty = existing.orElseGet(() -> new DirectoryGroup(
                            group.id(), group.externalId(), group.displayName(), Set.of()));

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        if (existing.isEmpty() && result.hasFailure()) {
                            return Mono.empty();
                        }
                        DirectoryGroup reconciled = reconcileGroupMembers(
                                existingOrEmpty, group, beforeTuples, afterTuples, result);
                        return Mono.defer(() -> state.saveGroup(reconciled));
                    };

                    return diffAndApply(before, after, RelationTuple.groupRef(group.id()), lease, commit);
                }));
    }

    /**
     * 직원 삭제. 그 직원이 속한 모든 조직에서 멤버십도 함께 지운다.
     *
     * <p>삭제 튜플이 실패한 조직은 멤버 목록을 원래대로 유지한다({@link #reconcileRemovedMember}).
     * 하나라도 실패하면 직원 레코드 자체도 지우지 않는다 — 지워버리면 다음 재시도가 diff 할
     * "이전"이 사라져 남은 튜플을 영원히 다시 잡지 못한다.
     */
    public Mono<IncrementalSyncResult> removeUser(String userId) {
        return withLock(lease -> removeUserInternal(userId, lease));
    }

    private Mono<IncrementalSyncResult> removeUserInternal(String userId, LockLease lease) {
        return state.findUser(userId)
                .flatMap(user -> affectedGroupsOf(userId).flatMap(groups -> {
                    Mono<DirectorySnapshot> before = snapshotOf(groups, Mono.just(user));
                    Set<DirectoryGroup> without = removeMemberFrom(groups, MemberRef.user(userId));
                    Mono<DirectorySnapshot> after = snapshotOf(without, Mono.empty());

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        Set<DirectoryGroup> reconciled = reconcileRemovedMember(
                                groups, without, MemberRef.user(userId), beforeTuples, result);
                        Mono<Void> saveGroups = Flux.fromIterable(reconciled)
                                .flatMap(state::saveGroup, LOAD_CONCURRENCY)
                                .then();
                        if (result.hasFailure()) {
                            return saveGroups;
                        }
                        return saveGroups.then(Mono.defer(() -> state.deleteUser(userId)));
                    };

                    return diffAndApply(before, after, RelationTuple.userRef(userId), lease, commit);
                }))
                .defaultIfEmpty(IncrementalSyncResult.noChange());
    }

    /**
     * 조직 삭제. 상위 조직에서의 child 튜플까지 함께 지운다.
     *
     * <p>상위 조직 쪽 삭제가 실패한 것은 그 상위 조직의 멤버 목록을 원래대로 유지하고
     * ({@link #reconcileRemovedMember}), 이 조직 자신의 멤버 튜플 삭제가 실패한 것은
     * 이 조직 자신의 멤버 목록에서 그 멤버를 남긴다({@link #reconcileGroupMembers} 를
     * "멤버 없는 목표"로 재사용). 하나라도 실패하면 이 조직 레코드 자체는 지우지 않는다.
     */
    public Mono<IncrementalSyncResult> removeGroup(String groupId) {
        return withLock(lease -> removeGroupInternal(groupId, lease));
    }

    private Mono<IncrementalSyncResult> removeGroupInternal(String groupId, LockLease lease) {
        return state.findGroup(groupId)
                .flatMap(group -> parentsOf(groupId).flatMap(parents -> {
                    Set<DirectoryGroup> beforeGroups = new LinkedHashSet<>(parents);
                    beforeGroups.add(group);
                    Mono<DirectorySnapshot> before = snapshotOfGroups(beforeGroups);

                    Set<DirectoryGroup> afterParents = removeMemberFrom(parents, MemberRef.group(groupId));
                    Mono<DirectorySnapshot> after = snapshotOfGroups(afterParents);

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        Set<DirectoryGroup> reconciledParents = reconcileRemovedMember(
                                parents, afterParents, MemberRef.group(groupId), beforeTuples, result);
                        Mono<Void> saveParents = Flux.fromIterable(reconciledParents)
                                .flatMap(state::saveGroup, LOAD_CONCURRENCY)
                                .then();

                        if (result.hasFailure()) {
                            DirectoryGroup emptyLike = new DirectoryGroup(
                                    group.id(), group.externalId(), group.displayName(), Set.of());
                            DirectoryGroup reconciledGroup = reconcileGroupMembers(
                                    group, emptyLike, beforeTuples, afterTuples, result);
                            return saveParents.then(Mono.defer(() -> state.saveGroup(reconciledGroup)));
                        }
                        return saveParents.then(Mono.defer(() -> state.deleteGroup(groupId)));
                    };

                    return diffAndApply(before, after, RelationTuple.groupRef(groupId), lease, commit);
                }))
                .defaultIfEmpty(IncrementalSyncResult.noChange());
    }

    // ---------- 공통 ----------

    /** {@code before}/{@code after} 튜플 집합과 반영 결과를 받아 실제 커밋을 수행한다. */
    @FunctionalInterface
    private interface Commit {
        Mono<Void> apply(TupleWriteResult result, Set<RelationTuple> before, Set<RelationTuple> after);
    }

    /**
     * 변경 하나를 락 안에서 실행한다 (설계 §4).
     *
     * <p><b>왜 유스케이스가 잡나.</b> 핸들러마다 넣으면 나중에 경로가 하나 늘 때 조용히 빠지고,
     * 그 빠진 곳이 하필 다른 인스턴스와 경합한다. 여기 두면 네 경로가 빠짐없이 덮이고
     * 경로가 늘어도 자동으로 포함된다 — 인메모리 {@code MutationGate} 가 인스턴스 하나 안에서
     * 같은 이유로 여기(구 버전의 이 자리)에 있었지만, 인스턴스가 둘이면 아무것도 막지 못했다
     * (설계 §4.5). 지금은 그 자리를 이 분산 락이 대신한다.
     *
     * <p><b>{@code work} 가 끝나면 반납한다 — 단, 두 틈은 이것으로 못 막는다.</b>
     * {@code work} 자체가 성공·실패·취소 어느 경로로 끝나든 {@code doFinally} 가
     * {@code lock.release(lease)} 를 부르는 것은 맞다. 하지만
     * <ol>
     *   <li>반납 호출 자체가 실패하면(스로틀, 네트워크 등) {@code .subscribe()} 가 구독자 없이
     *       구독하는 것이라 그 에러는 아무도 받지 않고 {@code Hooks.onErrorDropped} 로만 샌다 —
     *       재시도하지 않으므로 리스가 자연 만료될 때까지 이 인스턴스도 남도 다시 잡지 못한다.</li>
     *   <li>{@code lock.acquire} 내부에서 조건부 쓰기(DynamoDB PutItem)가 이미 성공한 뒤,
     *       그 결과가 구독자에게 리스로 전달되기 전에 구독이 취소되면 이 메서드는 그 리스를
     *       아예 손에 쥐지 못해 반납을 시도할 대상조차 없다 — {@code Mono.fromFuture} 는
     *       다운스트림 취소를 내부 {@code CompletableFuture} 취소로 전파하지 않으므로, 쓰기는
     *       이미 저장소에 반영된 채로 남는다.
     * </ol>
     * 두 경우 모두 락이 TTL 이 지날 때까지 묶인다 — 완벽한 상호 배제가 아니라는 설계 §4 의
     * 전제와 같은 종류의 틈이다.
     */
    private Mono<IncrementalSyncResult> withLock(Function<LockLease, Mono<IncrementalSyncResult>> work) {
        return lock.acquire(MutationLock.LockPurpose.WRITE)
                // 밀리초 단위로 쥐는 락이라 즉시 503 을 내면 재시도만 늘어난다. 짧게 기다려보고
                // 그래도 안 되면 그때의 503 이 IdP 에게 의미 있는 신호가 된다 (설계 §4.4).
                .retryWhen(Retry.fixedDelay(acquireRetries(), ACQUIRE_RETRY_DELAY)
                        .filter(LockUnavailableException.class::isInstance))
                .onErrorMap(Exceptions::isRetryExhausted,
                        error -> new LockUnavailableException("변경 락을 얻지 못했습니다"))
                .flatMap(lease -> Mono.defer(() -> work.apply(lease))
                        .doFinally(signal -> lock.release(lease).subscribe()));
    }

    /**
     * 기준선을 <b>OpenFGA 에 물어서</b> 만든다 (설계 §5).
     *
     * <p>전에는 {@code TupleMapper(변경 전 상태)} 를 기준선으로 썼다. 그것은 "있어야 했던 것"
     * 이라, 어긋난 튜플이 있어도 양쪽에서 똑같이 빠져 델타가 비었다 — 계산은 매번 정확하고
     * 틀린 곳을 볼 방법만 없었다.
     *
     * <p>후보는 {@code TupleMapper.candidateTuples} 로 뽑는다. `active` 필터를 적용하기 전의
     * 멤버십이어야 비활성 직원의 잘못 남은 튜플이 확인 대상에 들어온다. 거기서 다시
     * {@code focus}(이번 연산의 초점 엔티티)를 언급하는 것만 남긴다 — {@link #mentioning} 참고.
     *
     * <p><b>Check 가 실패하면 폴백하지 않는다.</b> 상태 기준선으로 돌아가면 조용히 옛 동작이
     * 되고, 그게 하필 어긋남이 생기는 순간이다. 실패시켜 IdP 가 재시도하게 둔다.
     *
     * <p><b>리스 재확인은 델타가 있을 때만 일어난다(설계 §4.7).</b> {@code lock.renew(lease)} 는
     * 델타가 비지 않은 분기 — 즉 실제로 {@code writer.apply} 가 OpenFGA 에 쓰기를 낼 분기 —
     * 에서만 부른다. 델타가 비면 OpenFGA 에 아무것도 쓰지 않고 곧바로 {@code commit} 으로
     * 넘어가며, 이 경로는 리스를 재확인하지 않는다. §4.7 이 요구하는 것은 "OpenFGA 쓰기 직전"
     * 재확인이고 이 경로엔 그 쓰기가 없으므로 스펙과 어긋나지 않는다 — 다만 재확인이 <b>모든
     * 커밋</b>에 걸린다고 읽으면 안 된다.
     *
     * <p><b>설계 §7.2 와의 의도적 차이(버그가 아니다).</b> 스펙 표는 "전부 실패 → 저장하지 않음"
     * 이라고 적었지만 여기서는 실패해도 {@code commit} 을 부른다. 각 연산의 커밋 로직이
     * 이미 "실제로 반영된 만큼만" 상태에 남기도록 되어 있어 <b>멤버십은 어차피 그대로 유지</b>
     * 되고, 그렇게 해서 남는 것은 조직/직원의 META 속성(displayName, email 같은 것)뿐이다.
     * 이 필드들은 어느 것도 튜플 식별자가 아니라서 저장돼도 다음 diff 를 오염시키지 않는다.
     * 오히려 이름 변경 같은 튜플과 무관한 수정이 튜플 실패에 발목잡히지 않아 스펙보다 낫다.
     * 단 하나의 예외가 <b>레코드의 존재 자체</b>다 — 그것은 부모의 child 엣지가 성립하는
     * 조건이므로 튜플 식별자에 해당한다. 그래서 {@link #upsertGroup} 은 새 조직에 한해,
     * {@link #removeUser}/{@link #removeGroup} 은 삭제에 한해 실패 시 존재 여부를 건드리지 않는다.
     */
    private Mono<IncrementalSyncResult> diffAndApply(Mono<DirectorySnapshot> beforeMono,
                                                      Mono<DirectorySnapshot> afterMono,
                                                      String focus,
                                                      LockLease lease,
                                                      Commit commit) {
        return Mono.zip(beforeMono, afterMono).flatMap(both -> {
            DirectorySnapshot beforeSnapshot = both.getT1();
            DirectorySnapshot afterSnapshot = both.getT2();

            Set<RelationTuple> 모든후보 = new LinkedHashSet<>();
            모든후보.addAll(TupleMapper.candidateTuples(beforeSnapshot));
            모든후보.addAll(TupleMapper.candidateTuples(afterSnapshot));
            Set<RelationTuple> candidates = mentioning(모든후보, focus);

            return checker.existing(candidates).flatMap(actual -> {
                // 상태 기준선(있어야 했던 것)과 Check 기준선(실제 있는 것)을 비교한다 —
                // 이 둘이 다르면 그것이 곧 어긋남이다(설계 §7). 델타 계산 자체는 여전히
                // Check 기준선(actual)을 쓴다; 여기서는 오직 관측만 한다.
                // 상태 기준선도 같은 술어로 좁힌다 — actual 이 초점 밖 튜플을 아예 담지
                // 않으므로, 좁히지 않으면 이 연산이 보지도 않은 튜플이 전부 missing 으로
                // 세어져 지표가 거짓말을 한다.
                Set<RelationTuple> 상태기준선 = mentioning(tuplesOf(beforeSnapshot), focus);
                int extra = (int) actual.stream().filter(t -> !상태기준선.contains(t)).count();
                int missing = (int) 상태기준선.stream().filter(t -> !actual.contains(t)).count();
                if (extra > 0 || missing > 0) {
                    log.warn("OpenFGA 어긋남 발견: 있어선 안 될 튜플 {}건, 빠진 튜플 {}건", extra, missing);
                    driftObserver.observed(extra, missing);
                }

                Set<RelationTuple> 원하는것 = mentioning(tuplesOf(afterSnapshot), focus);
                return withoutCycleCreatingEdges(actual, 원하는것).flatMap(after -> {
                    TupleDelta delta = TupleDiff.between(actual, after);

                    if (delta.isEmpty()) {
                        return commit.apply(TupleWriteResult.empty(), actual, after)
                                .thenReturn(IncrementalSyncResult.noChange());
                    }
                    // 쓰기 직전에 리스를 다시 확인한다 (설계 §4.7).
                    // renew 는 토큰 조건이 걸린 조건부 쓰기라, 성공했다는 것이 곧
                    // "아직 내가 쥐고 있다" 는 증거다 — 메모리에 든 expiresAt 을 보는 것과
                    // 달리 저장소가 답한다. 여기서 실패하면 GC 정지 등으로 리스를 잃은
                    // 것이므로, 늦은 쓰기를 내보내지 않고 멈춘다.
                    // writer.apply(delta) 를 Mono.defer 로 감싼다 — 감싸지 않으면 이 Java
                    // 표현식이 .then() 호출 시점에 곧바로 평가돼, renew 가 실패해도 그
                    // 평가(어댑터에 따라 부수효과가 있을 수 있다)가 이미 일어난 뒤다.
                    // defer 로 감싸야 renew 가 실제로 성공한 뒤에만 실행된다.
                    return lock.renew(lease)
                            .then(Mono.defer(() -> writer.apply(delta)))
                            .flatMap(result -> commit.apply(result, actual, after)
                                    .thenReturn(IncrementalSyncResult.of(result)));
                });
            });
        });
    }

    /**
     * 새로 생기는 child 엣지 가운데 조직 계층에 순환을 만드는 것을 걸러낸다(설계 §5.3).
     *
     * <p>{@link TupleMapper#toTuples} 도 같은 보장을 DFS 로 하지만 그것은 <b>스냅샷 안의</b>
     * 그래프만 본다. 최소 스냅샷은 참조로 딸려온 하위 조직을 일부러 멤버 없이 싣기 때문에
     * ({@link #expandWithReferencedGroups}) 두 홉 이상 떨어진 순환은 보이지 않는다. 그래서
     * 여기서 현재상태 저장소를 직접 타고 내려가 도달성을 확인한다 — 새 엣지의 자식으로부터
     * 부모에 이미 도달할 수 있다면 그 엣지는 순환을 닫는다.
     *
     * <p>버려진 엣지는 {@link #reconcileGroupMembers} 입장에서 "애초에 튜플이 필요 없던 멤버"와
     * 똑같이 취급된다 — 멤버십 자체는 상태에 남고 튜플만 생기지 않는다. 이는 전체 동기화에서
     * {@link TupleMapper} 가 순환 간선을 버릴 때와 같은 결과다.
     */
    private Mono<Set<RelationTuple>> withoutCycleCreatingEdges(Set<RelationTuple> before,
                                                               Set<RelationTuple> after) {
        List<RelationTuple> newEdges = after.stream()
                .filter(tuple -> tuple.relation().equals(RelationTuple.CHILD))
                .filter(tuple -> !before.contains(tuple))
                .toList();
        if (newEdges.isEmpty()) {
            return Mono.just(after);
        }
        CycleScan scan = new CycleScan();
        return Flux.fromIterable(newEdges)
                .concatMap(edge -> {
                    String child = stripType(edge.user());
                    String parent = stripType(edge.object());
                    return reaches(child, parent, scan)
                            .filter(Boolean::booleanValue)
                            .doOnNext(cycle -> log.warn("튜플 변환 경고: {}",
                                    "조직 '%s' → '%s' 간선이 순환을 만들어 제외합니다".formatted(parent, child)))
                            .map(cycle -> edge);
                })
                .collect(LinkedHashSet<RelationTuple>::new, Set::add)
                .map(dropped -> {
                    if (dropped.isEmpty()) {
                        return after;
                    }
                    Set<RelationTuple> kept = new LinkedHashSet<>(after);
                    kept.removeAll(dropped);
                    return kept;
                });
    }

    /**
     * 한 요청 안에서 일어나는 모든 순환 검사가 공유하는 작업 공간.
     *
     * <p><b>인접 리스트는 공유하고 visited 는 공유하지 않는다.</b> 어떤 조직의 하위 조직 목록은
     * 현재상태의 순수한 함수라서 요청 하나 안에서는 몇 번을 물어도 같은 답이다 — 그래서
     * 캐시해도 안전하고, 이게 실제 비용(DynamoDB 파티션 조회)의 대부분이다. 반면 visited 는
     * 엣지마다 출발점과 목표가 달라서 공유하면 답이 틀린다: 앞선 엣지가 훑고 지나간 노드를
     * 뒤 엣지가 건너뛰면, 그 노드 너머에 있는 목표에 도달하지 못했다고 잘못 결론 내린다.
     *
     * <p>{@code budget} 은 요청 하나가 펼칠 수 있는 노드 수의 상한이다. 캐시 덕분에 같은 조직을
     * 두 번 펼치지는 않으므로, 이 값은 사실상 "요청 하나가 훑을 수 있는 조직 수"다. 현실의
     * 조직도는 수천 개 규모라 {@value #MAX_GRAPH_EXPANSIONS} 를 넘길 일이 없다 — 넘긴다면
     * 병리적인 그래프이거나 버그이므로, 조용히 추측하는 대신 요청을 실패시킨다.
     */
    private static final class CycleScan {
        private final Map<String, List<String>> childIds = new LinkedHashMap<>();
        private int budget = MAX_GRAPH_EXPANSIONS;
    }

    /**
     * {@code from} 에서 하위 조직 간선을 따라 {@code target} 에 닿는지 현재상태 저장소를 훑어
     * 확인한다. 자기 자신도 도달한 것으로 본다 — 자기 자신을 하위 조직으로 넣는 것도 순환이다.
     */
    private Mono<Boolean> reaches(String from, String target, CycleScan scan) {
        if (from.equals(target)) {
            return Mono.just(true);
        }
        Set<String> visited = new LinkedHashSet<>();
        visited.add(from);
        // 너비 우선 확장을 Flux.expand 에 맡긴다. 레벨마다 walk 를 재귀 호출하면 계층 깊이만큼
        // 연산자가 중첩돼, 깊은 사슬에서 예산 검사가 걸리기도 전에 StackOverflowError 가 난다.
        // expand 는 내부적으로 반복 처리하므로 깊이가 스택을 쓰지 않고, any 는 목표를 만나는
        // 즉시 상위를 취소해 나머지 계층을 읽지 않는다.
        return Flux.just(from)
                .expand(groupId -> childIdsOf(groupId, scan)
                        .flatMapIterable(ids -> ids)
                        .filter(visited::add))
                .any(target::equals);
    }

    /**
     * 한 조직의 하위 조직 id 목록을 돌려준다. 요청 단위 캐시에 없을 때만 저장소를 읽고,
     * 읽을 때마다 예산을 하나 쓴다. 없는 조직은 빈 목록으로 캐시한다 — 부모가 참조하지만
     * 아직 도착하지 않은 조직이 흔하고, 그때마다 다시 읽을 이유가 없다.
     */
    private Mono<List<String>> childIdsOf(String groupId, CycleScan scan) {
        List<String> cached = scan.childIds.get(groupId);
        if (cached != null) {
            return Mono.just(cached);
        }
        if (scan.budget-- <= 0) {
            return Mono.error(new IllegalStateException(
                    "조직 계층 순환 검사가 %d개 조직을 넘겼습니다. 계층이 비정상적으로 크거나 깊습니다: %s"
                            .formatted(MAX_GRAPH_EXPANSIONS, groupId)));
        }
        return state.findGroup(groupId)
                .map(group -> group.members().stream()
                        .filter(member -> member.type() == MemberType.GROUP)
                        .map(MemberRef::id)
                        .toList())
                .defaultIfEmpty(List.of())
                .doOnNext(ids -> scan.childIds.put(groupId, ids));
    }

    private static String stripType(String typedId) {
        int separator = typedId.indexOf(':');
        return separator < 0 ? typedId : typedId.substring(separator + 1);
    }

    /**
     * 이번 연산의 <b>초점 엔티티</b>를 언급하는 튜플만 남긴다 (설계 §5.2).
     *
     * <p>최소 스냅샷은 영향 조직을 <b>멤버 목록째로</b> 싣는다 — child 엣지의 존재 조건과
     * 활성 판정에 필요하기 때문이다. 그래서 {@code candidateTuples} 를 그대로 쓰면 영향 조직의
     * <i>모든</i> 멤버가 후보가 되고, {@code PUT /Users/kim} 한 번이 5000명 조직 전체를
     * BatchCheck 하게 된다 — 전역 락을 쥔 채로. 설계 §5.2 는 정반대를 요구한다:
     * <i>"upsertUser 가 소속 조직의 전체 멤버를 확인하지 않는 것이 중요하다"</i>,
     * <i>"무관한 튜플까지 확인하면 비용만 늘고 삭제 범위만 위험해진다"</i>.
     *
     * <p><b>후보와 목표를 같은 술어로 좁혀야 한다.</b> 후보만 좁히면 {@code actual} 에는 없고
     * {@code after} 에는 있는 튜플이 생겨, 실제로는 멀쩡히 있는 튜플을 델타가 매번 다시 쓴다.
     * 관측용 상태 기준선도 같이 좁힌다 — 그래야 {@code extra}/{@code missing} 이 이 연산이
     * 실제로 본 범위를 뜻한다.
     *
     * <p>양쪽 자리를 다 보는 이유는 {@link RelationTuple#mentions} 참고 — {@code upsertGroup}
     * 의 후보에는 {@code child(DEV001, 상위조직)} 처럼 초점이 user 자리인 것도 들어간다.
     */
    private static Set<RelationTuple> mentioning(Set<RelationTuple> tuples, String focus) {
        Set<RelationTuple> narrowed = new LinkedHashSet<>();
        for (RelationTuple tuple : tuples) {
            if (tuple.mentions(focus)) {
                narrowed.add(tuple);
            }
        }
        return narrowed;
    }

    private Set<RelationTuple> tuplesOf(DirectorySnapshot snapshot) {
        var mapping = TupleMapper.toTuples(snapshot);
        mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
        return mapping.tuples();
    }

    /**
     * 반영이 실패하면 {@code active} 를 이전 값으로 되돌린 유저를 돌려준다. 다른 필드는
     * 요청값을 그대로 쓴다 — 튜플에 영향을 주는 것은 {@code active} 뿐이라, 실패했을 때
     * 그것만 되돌리면 다음 동기화가 같은 델타를 다시 계산해 재시도한다.
     */
    private static DirectoryUser reconcileUser(DirectoryUser existing, DirectoryUser requested, TupleWriteResult result) {
        if (!result.hasFailure()) {
            return requested;
        }
        return new DirectoryUser(requested.id(), requested.externalId(), requested.userName(),
                requested.displayName(), requested.email(), existing.active());
    }

    /**
     * 조직의 최종 멤버 목록을, 실제로 반영된 만큼만 계산한다.
     *
     * <p>새로 추가된 멤버는 <b>그 튜플이 지금 OpenFGA 에 있을 때</b> 저장된다 — 이번에 우리가
     * 썼거나({@code result.written()}), 이미 있어서 쓸 필요가 없었거나({@code beforeTuples},
     * 즉 Check 기준선), 애초에 튜플이 필요 없는 멤버거나(비활성 유저, 존재하지 않는 하위 조직).
     * 빠진 멤버는 그 튜플이 실제로 지워졌을 때만(또는 원래 튜플이 없었을 때) 제외된다 —
     * 삭제가 실패하면 여전히 멤버로 남아, 다음 동기화가 다시 지우려 시도한다.
     *
     * <p><b>"이미 있음" 을 빠뜨리면 안 된다.</b> 기준선이 상태였을 때는 새 멤버의 튜플이 언제나
     * 델타에 들어가 {@code written} 에 나타났다. 기준선이 OpenFGA 로 바뀐 지금은 <b>이미 있는
     * 튜플이 델타에서 빠지므로</b> {@code written} 에도 없다. {@code written} 만 보면 그 멤버가
     * 조용히 누락되고, 멤버십이 없으니 다음 연산의 후보에도 들어오지 않아 영원히 고쳐지지
     * 않는다 — OpenFGA 쓰기 성공 뒤 DynamoDB 커밋이 실패해 IdP 가 같은 요청을 재시도하는,
     * 이 기능이 없애려는 바로 그 경로다.
     *
     * <p>{@code requested} 의 멤버를 빈 집합으로 주면 "이 조직을 통째로 비우려는 시도"를
     * 표현할 수 있다 — {@link #removeGroup} 이 자기 자신의 멤버 튜플 삭제를 이 방식으로
     * 재사용한다.
     */
    private DirectoryGroup reconcileGroupMembers(DirectoryGroup existing,
                                                 DirectoryGroup requested,
                                                 Set<RelationTuple> beforeTuples,
                                                 Set<RelationTuple> afterTuples,
                                                 TupleWriteResult result) {
        Set<MemberRef> beforeMembers = existing.members();
        Set<MemberRef> requestedMembers = requested.members();
        Set<MemberRef> persisted = new LinkedHashSet<>();

        for (MemberRef member : requestedMembers) {
            if (beforeMembers.contains(member)) {
                persisted.add(member); // 변경 없는 멤버
                continue;
            }
            RelationTuple tuple = tupleFor(member, requested.id());
            boolean expected = afterTuples.contains(tuple);
            boolean inOpenFga = result.written().contains(tuple) || beforeTuples.contains(tuple);
            if (!expected || inOpenFga) {
                persisted.add(member);
            }
            // else: 튜플 반영 실패 -> 제외한 채로 둬서 다음 동기화가 재시도하게 한다
        }
        for (MemberRef member : beforeMembers) {
            if (requestedMembers.contains(member)) {
                continue; // 위에서 이미 유지됨
            }
            RelationTuple tuple = tupleFor(member, requested.id());
            boolean existedBefore = beforeTuples.contains(tuple);
            if (existedBefore && !result.deleted().contains(tuple)) {
                persisted.add(member); // 삭제 반영 실패 -> 여전히 멤버
            }
            // else: 원래 튜플이 없었거나(비활성 유저 등) 삭제가 성공 -> 제외 유지
        }

        return new DirectoryGroup(requested.id(), requested.externalId(), requested.displayName(), persisted);
    }

    /**
     * {@code ref}(직원 또는 하위 조직)를 {@code originalGroups} 각각에서 빼려던 결과를,
     * 실제로 삭제 튜플이 반영된 조직만 골라 되돌린다. 삭제가 실패한 조직은
     * {@code originalGroups} 의 원래 멤버 목록을 그대로 유지해, 다음 동기화가 diff 할
     * "이전"을 보존하고 재시도가 가능하게 한다.
     */
    private static Set<DirectoryGroup> reconcileRemovedMember(Set<DirectoryGroup> originalGroups,
                                                               Set<DirectoryGroup> withoutGroups,
                                                               MemberRef ref,
                                                               Set<RelationTuple> beforeTuples,
                                                               TupleWriteResult result) {
        Map<String, DirectoryGroup> originalById = byId(originalGroups);
        Set<DirectoryGroup> reconciled = new LinkedHashSet<>();
        for (DirectoryGroup candidate : withoutGroups) {
            RelationTuple tuple = tupleFor(ref, candidate.id());
            boolean existedBefore = beforeTuples.contains(tuple);
            if (existedBefore && !result.deleted().contains(tuple)) {
                reconciled.add(originalById.get(candidate.id())); // 삭제 실패 -> 원래 멤버 목록 유지
            } else {
                reconciled.add(candidate); // 삭제 성공, 또는 애초에 튜플이 없었음
            }
        }
        return reconciled;
    }

    private static RelationTuple tupleFor(MemberRef member, String groupId) {
        return member.type() == MemberType.USER
                ? RelationTuple.directMember(member.id(), groupId)
                : RelationTuple.child(member.id(), groupId);
    }

    /** 이 직원이 속한 모든 조직. 활성 여부가 뒤집히면 전부 영향을 받는다. */
    private Mono<Set<DirectoryGroup>> affectedGroupsOf(String userId) {
        return state.findGroupIdsContaining(MemberRef.user(userId))
                .flatMap(state::findGroup, LOAD_CONCURRENCY)
                .collect(LinkedHashSet<DirectoryGroup>::new, Set::add);
    }

    /** 이 조직을 하위 조직으로 갖는 상위 조직들. */
    private Mono<Set<DirectoryGroup>> parentsOf(String groupId) {
        return state.findGroupIdsContaining(MemberRef.group(groupId))
                .flatMap(state::findGroup, LOAD_CONCURRENCY)
                .collect(LinkedHashSet<DirectoryGroup>::new, Set::add);
    }

    private Mono<DirectorySnapshot> snapshotOfGroups(Set<DirectoryGroup> groups) {
        return snapshotOf(groups, Mono.empty());
    }

    /**
     * 조직 집합과 (선택적) 변경된 유저 하나로 최소 스냅샷을 만든다. 조직의 멤버 유저를
     * 모두 실어야 {@link TupleMapper} 가 활성 여부를 판정할 수 있고, 멤버로 참조된
     * 하위 조직의 존재도 실어야 {@link TupleMapper} 가 child 엣지를 만들 수 있다.
     */
    private Mono<DirectorySnapshot> snapshotOf(Set<DirectoryGroup> groups, Mono<DirectoryUser> changed) {
        return expandWithReferencedGroups(groups)
                .flatMap(allGroups -> changed.map(Set::of).defaultIfEmpty(Set.of())
                        .flatMap(overrides -> loadMemberUsers(allGroups, overrides)
                                .map(users -> new DirectorySnapshot(users, byId(allGroups)))));
    }

    /**
     * {@code groups} 의 멤버 중 GROUP 타입인데 집합에 없는 것을 현재상태에서 읽어와 채운다.
     * {@link TupleMapper} 는 하위 조직이 스냅샷의 groups 맵에 없으면 child 엣지를 건너뛰고
     * 경고만 남기기 때문이다.
     *
     * <p><b>멤버는 비운 채로 채운다.</b> 읽어온 하위 조직을 실제 멤버 목록 그대로 채우면,
     * {@link TupleMapper#collectDirectMembers} 가 스냅샷에 있는 <i>모든</i> 조직의 direct_member
     * 튜플을 만든다는 사실과 부딪힌다 — 이 하위 조직이 참조 대상으로 딸려 들어오는지 여부는
     * 정확히 지금 바뀌는 것(부모의 멤버 목록)에 달려 있으므로, before/after 스냅샷 중 한쪽에만
     * 나타나면 그 하위 조직 <b>자신의</b>, 이 연산과 무관한 멤버 튜플이 델타에 새어 들어가
     * 지워지거나(또는 스푸리어스하게 다시 쓰이고) 만다. 존재만 확인하면 되므로 멤버를 비워서
     * {@code containsKey} 는 통과시키되 {@code collectDirectMembers}/{@code collectChildEdges} 가
     * 이 하위 조직으로부터는 아무 튜플도 만들지 않게 한다 — 그러면 어느 쪽 스냅샷에 들어있든
     * 기여가 0이라 결과가 대칭이다. 하위 구조를 재귀적으로 채우지 않는 것도 같은 이유다.
     */
    private Mono<Set<DirectoryGroup>> expandWithReferencedGroups(Set<DirectoryGroup> groups) {
        Set<String> knownIds = new LinkedHashSet<>();
        groups.forEach(g -> knownIds.add(g.id()));

        Set<String> missingIds = new LinkedHashSet<>();
        for (DirectoryGroup g : groups) {
            for (MemberRef member : g.members()) {
                if (member.type() == MemberType.GROUP && !knownIds.contains(member.id())) {
                    missingIds.add(member.id());
                }
            }
        }
        if (missingIds.isEmpty()) {
            return Mono.just(groups);
        }
        return Flux.fromIterable(missingIds)
                .flatMap(state::findGroup, LOAD_CONCURRENCY)
                .map(loaded -> new DirectoryGroup(loaded.id(), loaded.externalId(), loaded.displayName(), Set.of()))
                .collect(LinkedHashSet<DirectoryGroup>::new, Set::add)
                .map(loaded -> {
                    Set<DirectoryGroup> merged = new LinkedHashSet<>(groups);
                    merged.addAll(loaded);
                    return merged;
                });
    }

    /**
     * 조직들의 멤버 유저를 현재상태에서 읽어온다. {@code overrides} 에 있는 유저는
     * 저장된 값 대신 그 값을 쓴다 — 아직 저장 전인 변경 후 상태를 반영하기 위해서다.
     */
    private Mono<Map<String, DirectoryUser>> loadMemberUsers(Set<DirectoryGroup> groups,
                                                              Set<DirectoryUser> overrides) {
        Map<String, DirectoryUser> overrideById = byUserId(overrides);
        Set<String> memberIds = new LinkedHashSet<>();
        for (DirectoryGroup group : groups) {
            for (MemberRef member : group.members()) {
                if (member.type() == MemberType.USER) {
                    memberIds.add(member.id());
                }
            }
        }
        memberIds.addAll(overrideById.keySet());

        return Flux.fromIterable(memberIds)
                .flatMap(id -> overrideById.containsKey(id)
                        ? Mono.just(overrideById.get(id))
                        : state.findUser(id), LOAD_CONCURRENCY)
                .collect(LinkedHashMap<String, DirectoryUser>::new, (map, user) -> map.put(user.id(), user));
    }

    private static Set<DirectoryGroup> removeMemberFrom(Set<DirectoryGroup> groups, MemberRef ref) {
        Set<DirectoryGroup> result = new LinkedHashSet<>();
        for (DirectoryGroup group : groups) {
            Set<MemberRef> members = new LinkedHashSet<>(group.members());
            members.remove(ref);
            result.add(new DirectoryGroup(group.id(), group.externalId(), group.displayName(), members));
        }
        return result;
    }

    private static Map<String, DirectoryGroup> byId(Set<DirectoryGroup> groups) {
        Map<String, DirectoryGroup> map = new LinkedHashMap<>();
        groups.forEach(group -> map.put(group.id(), group));
        return map;
    }

    private static Map<String, DirectoryUser> byUserId(Set<DirectoryUser> users) {
        Map<String, DirectoryUser> map = new LinkedHashMap<>();
        users.forEach(user -> map.put(user.id(), user));
        return map;
    }
}

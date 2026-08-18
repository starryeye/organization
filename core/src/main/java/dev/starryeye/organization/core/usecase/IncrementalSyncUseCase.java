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
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
 *   <li>조직 변경 — 그 조직 + 그 조직의 멤버 유저들(활성 여부 판정에 필요) + 멤버로
 *       참조된 하위 조직의 <b>존재</b>(존재 확인에 필요, {@link TupleMapper} 가 child 엣지를
 *       만들려면 그 하위 조직이 스냅샷에 있어야 한다 — 단, 그 하위 조직 자신의 멤버까지
 *       실으면 안 된다. {@link #expandWithReferencedGroups} 참고)</li>
 *   <li>유저 변경 — 그 유저 + 그 유저가 속한 모든 조직({@code findGroupIdsContaining} 으로
 *       찾음). {@code active} 가 뒤집히면 그 유저의 모든 {@code direct_member} 튜플이
 *       생기거나 사라진다</li>
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

    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;

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
     */
    public Mono<IncrementalSyncResult> upsertUser(DirectoryUser user) {
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

                            return diffAndApply(before, after, commit);
                        }));
    }

    /**
     * 조직 생성·수정. 멤버 목록을 통째로 교체한다.
     *
     * <p>부분 실패 시에는 요청된 멤버 목록을 그대로 저장하지 않는다. 실제로 튜플이 반영된
     * 멤버만 추가되고, 실제로 튜플이 지워진 멤버만 제외된다({@link #reconcileGroupMembers}).
     */
    public Mono<IncrementalSyncResult> upsertGroup(DirectoryGroup group) {
        DirectoryGroup emptyLike = new DirectoryGroup(group.id(), group.externalId(), group.displayName(), Set.of());

        return state.findGroup(group.id())
                .defaultIfEmpty(emptyLike)
                .flatMap(existingGroup -> {
                    Mono<DirectorySnapshot> before = snapshotOfGroups(Set.of(existingGroup));
                    Mono<DirectorySnapshot> after = snapshotOfGroups(Set.of(group));

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        DirectoryGroup reconciled = reconcileGroupMembers(
                                existingGroup, group, beforeTuples, afterTuples, result);
                        return Mono.defer(() -> state.saveGroup(reconciled));
                    };

                    return diffAndApply(before, after, commit);
                });
    }

    /**
     * 직원 삭제. 그 직원이 속한 모든 조직에서 멤버십도 함께 지운다.
     *
     * <p>삭제 튜플이 실패한 조직은 멤버 목록을 원래대로 유지한다({@link #reconcileRemovedMember}).
     * 하나라도 실패하면 직원 레코드 자체도 지우지 않는다 — 지워버리면 다음 재시도가 diff 할
     * "이전"이 사라져 남은 튜플을 영원히 다시 잡지 못한다.
     */
    public Mono<IncrementalSyncResult> removeUser(String userId) {
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

                    return diffAndApply(before, after, commit);
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

                    return diffAndApply(before, after, commit);
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
     * 변경 전후 스냅샷을 튜플로 바꿔 diff 하고, OpenFGA 에 먼저 적용한 뒤 {@code commit} 으로
     * 상태를 커밋한다. 변경이 없으면 {@link TupleWriteResult#empty()} 로 {@code commit} 을 호출한다
     * (아무것도 실패하지 않았으므로 각 연산의 커밋 로직이 요청값을 그대로 반영해도 안전하다).
     * 변경이 있으면 실제 {@code writer.apply} 결과로 호출한다 — 성공·부분실패 어느 쪽이든
     * {@code commit} 은 항상 실행된다.
     */
    private Mono<IncrementalSyncResult> diffAndApply(Mono<DirectorySnapshot> beforeMono,
                                                      Mono<DirectorySnapshot> afterMono,
                                                      Commit commit) {
        return Mono.zip(beforeMono, afterMono).flatMap(both -> {
            Set<RelationTuple> before = tuplesOf(both.getT1());
            Set<RelationTuple> after = tuplesOf(both.getT2());
            TupleDelta delta = TupleDiff.between(before, after);

            if (delta.isEmpty()) {
                return commit.apply(TupleWriteResult.empty(), before, after)
                        .thenReturn(IncrementalSyncResult.noChange());
            }
            return writer.apply(delta)
                    .flatMap(result -> commit.apply(result, before, after)
                            .thenReturn(IncrementalSyncResult.of(result)));
        });
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
     * <p>새로 추가된 멤버는 그 튜플이 실제로 기록됐을 때만(또는 처음부터 튜플이 필요 없을
     * 때, 예: 비활성 유저나 존재하지 않는 하위 조직) 저장된다. 빠진 멤버는 그 튜플이 실제로
     * 지워졌을 때만(또는 원래 튜플이 없었을 때) 제외된다 — 삭제가 실패하면 여전히 멤버로
     * 남아, 다음 동기화가 다시 지우려 시도한다.
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
            if (!expected || result.written().contains(tuple)) {
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

package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeStateRepository implements DirectoryStateRepository {

    /** {@link #findAssignedParent} 가 받은 조직 id — 읽은 차례대로. 읽기가 동시에 일어날 수 있어 동시 접근에 안전한 목록이다. */
    public final List<String> findAssignedParentCalls = Collections.synchronizedList(new ArrayList<>());
    /** 여기 든 조직의 다음 {@link #findAssignedParent} 는 {@code IllegalStateException} 으로 끝나고, 그 조직을 집합에서 뺀다. */
    public final Set<String> findAssignedParentFailsOnce = ConcurrentHashMap.newKeySet();

    public final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    public final Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

    /**
     * {@link #findGroup} 이 불린 순서대로의 조직 id. 읽기 횟수 자체를 단언하고 싶은
     * 테스트(요청 단위 캐시가 실제로 먹는지 등)를 위한 계측이며, 그 밖의 동작에는
     * 영향을 주지 않는다.
     */
    public final List<String> findGroupCalls = new ArrayList<>();

    /**
     * {@link #findGroupHeader} 가 불린 순서대로의 조직 id. {@link #findGroupCalls} 와
     * 같은 목적의 계측이다.
     */
    public final List<String> findGroupHeaderCalls = new ArrayList<>();

    /** {@link #findUser} 가 불린 순서대로의 직원 아이디. {@link #findGroupCalls} 와 같은 목적. */
    public final List<String> findUserCalls = new ArrayList<>();

    /** {@link #findUsers} 가 받은 아이디 묶음 — 멤버 직원을 묶어 읽는지 단언한다. */
    public final List<Set<String>> findUsersCalls = new ArrayList<>();

    /** {@link #findMemberTypes} 가 받은 아이디 묶음. */
    public final List<Set<String>> findMemberTypesCalls = new ArrayList<>();

    /** {@link #findMembers} 가 받은 후보들. 무엇을 물었는지 단언하는 계측이다. */
    public final List<Set<MemberRef>> findMembersCalls = new ArrayList<>();

    /** {@link #findMemberRefs} 가 불린 순서대로의 조직 id. */
    public final List<String> findMemberRefsCalls = new ArrayList<>();

    /** {@link #findGroupIdsContaining} 이 받은 멤버의 아이디 — 순환 검사가 위로 몇 조직을 읽는지 단언한다. */
    public final List<String> findGroupIdsContainingCalls = new ArrayList<>();

    /** 쓰기 순서 — {@code saveGroup:<id>}, {@code saveGroupChange:<id>}, {@code 보류+}, {@code 보류-}. 보류 줄이 멤버 줄보다 먼저인지 단언한다. */
    public final List<String> 쓴순서 = new ArrayList<>();

    /** {@link #deleteGroup} 가 불린 조직 id — 삭제 전용 경로가 조직을 몇 번 지우는지 본다. */
    public final List<String> deleteGroupCalls = new ArrayList<>();

    /** 보류 목록(설계 2026-10-03 §4.1). {@link #replaceWith} 는 건드리지 않는다 — 실제 저장소도 그렇다. */
    public final Set<GroupEdge> cutEdges = new LinkedHashSet<>();

    /**
     * 지정 연결(설계 2026-11-04 §4) — 하위 조직 → 상위 조직. 조직의 멤버({@link DirectoryGroup#members})와 따로 둔다. {@link #deleteGroup} 은 그 조직이
     * 하위이거나 상위인 줄을 지우고, {@link #replaceWith} 는 끝에서 조직이 없는 쪽이 낀 줄을 지운다(wipe).
     */
    public final Map<String, String> assignedParents = new LinkedHashMap<>();

    /** {@link #saveAssignedParent} 가 받은 {@code [orgCode, parentOrgCode, previousParentOrgCode]}(이전이 없으면 null) — 넘긴 이전 값을 단언한다. */
    public final List<List<String>> saveAssignedParentCalls = new ArrayList<>();

    /**
     * 유일 값 표지판(설계 2026-10-14 §3) — 차지·넘겨받기로 적은 것. 직원·조직 지도에 바로 심은 것은 여기 적지 않아도 지금 값의 표지판을
     * 오래전({@link Instant#EPOCH})에 쥔 것으로 본다({@link #표지판}). 실제 저장소에서 SCIM 으로 만든 직원·조직은 늘 자기 값의 표지판을 쥐고 있다.
     */
    public final Map<UniqueKind, Map<String, UniqueClaim>> uniqueMarkers = new EnumMap<>(UniqueKind.class);

    /** 설정하면 {@link #releaseUnique} 가 이 예외로 끝난다 — 놓기 실패를 흉내 낸다. */
    public volatile RuntimeException failReleaseUnique;

    /** 설정하면 {@link #changeCutEdges} 가 넣을 줄이 있을 때 아무것도 바꾸지 않고 이 예외로 끝난다 — 앞쪽 보류 쓰기 실패를 흉내 낸다. */
    public volatile RuntimeException failCutEdgesAdding;

    /** 설정하면 {@link #changeCutEdges} 가 뺄 줄이 있을 때 아무것도 바꾸지 않고 이 예외로 끝난다 — 뒤쪽 보류 목록 정리 실패를 흉내 낸다. */
    public volatile RuntimeException failCutEdgesRemoving;

    /** 설정하면 {@link #replaceCutEdges} 가 이 예외로 끝난다 — 보류 목록 저장 실패를 흉내 낸다. */
    public volatile RuntimeException failReplaceCutEdges;

    /** 설정하면 {@link #clearUniqueMarkers} 가 이 예외로 끝난다 — 표지판 비우기 실패를 흉내 낸다. */
    public volatile RuntimeException failClearUniqueMarkers;

    /**
     * 설정하면 {@link #replaceWith(DirectorySnapshot, DirectoryChanges.Listener)} 가 변경을 이만큼 마친 뒤, 남은 변경이 있으면
     * {@code IllegalStateException} 으로 끝난다 — 도중에 끊긴 전체 교체를 흉내 낸다. 0 이면 아무것도 바꾸지 않고 실패한다. 음수면 실패하지 않는다.
     */
    public volatile int failReplaceWithAfter = -1;

    /** {@link #findCutEdges} 가 불린 수 — 보류 목록을 언제 읽는지 단언한다. */
    public int findCutEdgesCalls;

    private RuntimeException loadAllFailure;

    /** 설정하면 {@link #loadAll} 이 이 예외로 실패한다. 재적재가 읽기에 실패하는 경로를 보는 데 쓴다. */
    public void failLoadAll(RuntimeException failure) {
        this.loadAllFailure = failure;
    }

    @Override
    public Mono<DirectoryUser> findUser(String userId) {
        return Mono.fromRunnable(() -> findUserCalls.add(userId))
                .then(Mono.justOrEmpty(users.get(userId)));
    }

    @Override
    public Flux<DirectoryUser> findUsers(Set<String> userIds) {
        return Flux.defer(() -> {
            findUsersCalls.add(Set.copyOf(userIds));
            return Flux.fromIterable(userIds).flatMap(id -> Mono.justOrEmpty(users.get(id)));
        });
    }

    @Override
    public Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids) {
        return Mono.fromCallable(() -> {
            findMemberTypesCalls.add(Set.copyOf(ids));
            Map<String, MemberType> found = new LinkedHashMap<>();
            for (String id : ids) {
                if (groups.containsKey(id)) {
                    found.put(id, MemberType.GROUP);
                } else if (users.containsKey(id)) {
                    found.put(id, MemberType.USER);
                }
            }
            return found;
        });
    }

    @Override
    public Flux<String> findUserIdsByExternalId(String externalId) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(users.values()))
                .filter(user -> externalId != null && externalId.equals(user.externalId()))
                .map(DirectoryUser::id));
    }

    @Override
    public Flux<String> findGroupIdsByExternalId(String externalId) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(groups.values()))
                .filter(group -> externalId != null && externalId.equals(group.externalId()))
                .map(DirectoryGroup::id));
    }

    @Override
    public Mono<DirectoryGroup> findGroup(String groupId) {
        return Mono.fromRunnable(() -> findGroupCalls.add(groupId))
                .then(Mono.justOrEmpty(groups.get(groupId)));
    }

    @Override
    public Mono<GroupHeader> findGroupHeader(String groupId) {
        return Mono.fromRunnable(() -> findGroupHeaderCalls.add(groupId))
                .then(Mono.justOrEmpty(groups.get(groupId)))
                .map(group -> new GroupHeader(
                        group.id(), group.externalId(), group.displayName()));
    }

    /** 읽지 않고 그대로 넣는다 — 시드에 쓰여 읽기 계측({@link #findUserCalls})을 더럽히지 않게 포트 default 를 덮는다. */
    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        users.put(user.id(), user);
        return Mono.empty();
    }

    @Override
    public Mono<Void> saveUser(DirectoryUser before, DirectoryUser after) {
        return saveUser(after);
    }

    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        groups.put(group.id(), group);
        쓴순서.add("saveGroup:" + group.id());
        return Mono.empty();
    }

    @Override
    public Mono<Void> deleteUser(String userId) {
        users.remove(userId);
        return Mono.empty();
    }

    @Override
    public Mono<Void> deleteGroup(String groupId, Set<MemberRef> members) {
        return Mono.fromRunnable(() -> {
            deleteGroupCalls.add(groupId);
            groups.remove(groupId);
            assignedParents.entrySet().removeIf(줄 -> groupId.equals(줄.getKey()) || groupId.equals(줄.getValue()));
            MemberRef 이조직 = MemberRef.group(groupId);
            groups.replaceAll((id, group) -> {
                if (!group.members().contains(이조직)) {
                    return group;
                }
                Set<MemberRef> 남은멤버 = new LinkedHashSet<>(group.members());
                남은멤버.remove(이조직);
                return new DirectoryGroup(group.id(), group.externalId(), group.displayName(), 남은멤버);
            });
        });
    }

    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        return Flux.defer(() -> {
            findGroupIdsContainingCalls.add(ref.id());
            return Flux.fromIterable(groups.values())
                    .filter(group -> group.members().contains(ref))
                    .map(DirectoryGroup::id);
        });
    }

    @Override
    public Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates) {
        return Mono.fromCallable(() -> {
            findMembersCalls.add(Set.copyOf(candidates));
            Set<MemberRef> found = new LinkedHashSet<>();
            candidates.stream().filter(membersOf(groupId)::contains).forEach(found::add);
            return found;
        });
    }

    @Override
    public Flux<MemberRef> findMemberRefs(String groupId) {
        return Flux.defer(() -> {
            findMemberRefsCalls.add(groupId);
            return Flux.fromIterable(membersOf(groupId));
        });
    }

    @Override
    public Mono<String> findAssignedParent(String orgCode) {
        return Mono.defer(() -> {
            findAssignedParentCalls.add(orgCode);
            if (findAssignedParentFailsOnce.remove(orgCode)) {
                return Mono.error(new IllegalStateException("지정 읽기 실패(테스트)"));
            }
            return Mono.justOrEmpty(assignedParents.get(orgCode));
        });
    }

    /** 아이디 오름차순 — 실제 저장소의 정렬키 순서와 같다. */
    @Override
    public Flux<String> findAssignedChildren(String orgCode) {
        return Flux.defer(() -> Flux.fromIterable(assignedParents.entrySet().stream()
                .filter(줄 -> orgCode.equals(줄.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList()));
    }

    @Override
    public Mono<Void> saveAssignedParent(String orgCode, String parentOrgCode, String previousParentOrgCode) {
        return Mono.fromRunnable(() -> {
            saveAssignedParentCalls.add(Arrays.asList(orgCode, parentOrgCode, previousParentOrgCode));
            assignedParents.put(orgCode, parentOrgCode);
        });
    }

    /** 정본이 {@code parentOrgCode} 를 가리킬 때만 지운다 — 실제 저장소의 조건부 삭제와 같다. */
    @Override
    public Mono<Void> deleteAssignedParent(String orgCode, String parentOrgCode) {
        return Mono.fromRunnable(() -> assignedParents.remove(orgCode, parentOrgCode));
    }

    /** 헤더를 읽지 않는다 — {@link #saveUser(DirectoryUser)} 와 같은 이유로 포트 default 를 덮는다. */
    @Override
    public Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed) {
        return saveGroupChange(null, header, added, removed);
    }

    @Override
    public Mono<Void> saveGroupChange(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed) {
        return Mono.fromRunnable(() -> {
            쓴순서.add("saveGroupChange:" + after.id());
            Set<MemberRef> members = new LinkedHashSet<>(membersOf(after.id()));
            members.removeAll(removed);
            members.addAll(added);
            groups.put(after.id(), new DirectoryGroup(after.id(), after.externalId(), after.displayName(), members));
        });
    }

    private Set<MemberRef> membersOf(String groupId) {
        DirectoryGroup group = groups.get(groupId);
        return group == null ? Set.of() : group.members();
    }

    @Override
    public Flux<GroupEdge> findCutEdges() {
        return Flux.defer(() -> {
            findCutEdgesCalls++;
            return Flux.fromIterable(List.copyOf(cutEdges));
        });
    }

    @Override
    public Mono<Void> changeCutEdges(Set<GroupEdge> added, Set<GroupEdge> removed) {
        return Mono.defer(() -> {
            if (failCutEdgesAdding != null && !added.isEmpty()) {
                return Mono.error(failCutEdgesAdding);
            }
            if (failCutEdgesRemoving != null && !removed.isEmpty()) {
                return Mono.error(failCutEdgesRemoving);
            }
            return Mono.fromRunnable(() -> {
                if (!added.isEmpty()) {
                    쓴순서.add("보류+");
                }
                if (!removed.isEmpty()) {
                    쓴순서.add("보류-");
                }
                cutEdges.addAll(added);
                cutEdges.removeAll(removed);
            });
        });
    }

    @Override
    public Mono<Void> replaceCutEdges(Set<GroupEdge> edges) {
        return Mono.defer(() -> failReplaceCutEdges != null
                ? Mono.error(failReplaceCutEdges)
                : Mono.fromRunnable(() -> {
                    cutEdges.clear();
                    cutEdges.addAll(edges);
                }));
    }

    @Override
    public Mono<UniqueClaim> claimUnique(UniqueKind kind, String key, String ownerId, Instant at) {
        return Mono.fromCallable(() -> {
            UniqueClaim 있던것 = 표지판(kind, key);
            if (있던것 != null) {
                return 있던것;
            }
            적은_표지판(kind).put(key, new UniqueClaim(ownerId, at));
            return null;
        });
    }

    @Override
    public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
        return Mono.fromCallable(() -> {
            UniqueClaim 있던것 = 표지판(kind, key);
            if (있던것 == null || !있던것.ownerId().equals(previousOwnerId)) {
                return false;
            }
            적은_표지판(kind).put(key, new UniqueClaim(ownerId, at));
            return true;
        });
    }

    @Override
    public Mono<Void> releaseUnique(UniqueKind kind, String key, String ownerId) {
        return Mono.defer(() -> {
            if (failReleaseUnique != null) {
                return Mono.error(failReleaseUnique);
            }
            UniqueClaim 있던것 = 적은_표지판(kind).get(key);
            if (있던것 != null && 있던것.ownerId().equals(ownerId)) {
                적은_표지판(kind).remove(key);
            }
            return Mono.empty();
        });
    }

    @Override
    public Mono<Void> clearUniqueMarkers() {
        return Mono.defer(() -> failClearUniqueMarkers != null
                ? Mono.error(failClearUniqueMarkers)
                : Mono.fromRunnable(uniqueMarkers::clear));
    }

    /**
     * 적은 표지판의 주인에 더해, 직원·조직 지도에 심은 것 중 그 값이 있는 것을 오래된 표지판의 주인으로 본다({@link #표지판}). 직원·조직을 읽지 않는다 —
     * 읽기 계측({@link #findUsersCalls}·{@link #findGroupCalls})을 더럽히지 않는다.
     */
    @Override
    public Flux<String> findUniqueOwners(UniqueKind kind) {
        return Flux.defer(() -> {
            List<String> owners = new ArrayList<>();
            uniqueMarkers.getOrDefault(kind, Map.of()).values().forEach(claim -> owners.add(claim.ownerId()));
            switch (kind) {
                case USER_NAME -> users.values().stream()
                        .filter(user -> user.userName() != null && !user.userName().isBlank())
                        .forEach(user -> owners.add(user.id()));
                case GROUP_EXTERNAL_ID -> groups.values().stream()
                        .filter(group -> group.externalId() != null && !group.externalId().isBlank())
                        .forEach(group -> owners.add(group.id()));
            }
            return Flux.fromIterable(owners);
        });
    }

    /** 키의 표지판 — 적은 것이 먼저고, 없으면 지금 그 값을 쥔 직원·조직을 오래된 표지판으로 본다. 없으면 null. */
    private UniqueClaim 표지판(UniqueKind kind, String key) {
        UniqueClaim 적은것 = 적은_표지판(kind).get(key);
        if (적은것 != null) {
            return 적은것;
        }
        return switch (kind) {
            case USER_NAME -> users.values().stream()
                    .filter(user -> user.userName() != null && !user.userName().isBlank()
                            && kind.key(user.userName()).equals(key))
                    .findFirst()
                    .map(user -> new UniqueClaim(user.id(), Instant.EPOCH))
                    .orElse(null);
            case GROUP_EXTERNAL_ID -> groups.values().stream()
                    .filter(group -> group.externalId() != null && !group.externalId().isBlank()
                            && kind.key(group.externalId()).equals(key))
                    .findFirst()
                    .map(group -> new UniqueClaim(group.id(), Instant.EPOCH))
                    .orElse(null);
        };
    }

    private Map<String, UniqueClaim> 적은_표지판(UniqueKind kind) {
        return uniqueMarkers.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
    }

    /**
     * 저장소와 같은 종류의 비교 결과를 돌려준다(설계 2026-10-10 §4.3) — 바뀐 직원·조직, 들어오고 나간 멤버, 사라진 것. 다만 사라진 직원을 지우며 그
     * 직원을 아직 멤버로 적은 조직의 멤버 줄까지 지우는 연쇄(실제 저장소의 "멤버만 빠짐")는 흉내 내지 않는다 — 그 결과는 저장소 테스트가 본다.
     *
     * <p>실제 저장소의 순서(직원 쓰기 → 조직 쓰기 → 사라진 조직 지우기 → 사라진 직원 지우기)로 하나씩 바꾸고, 바꿀 때마다 {@code 변경마다} 로 알린다
     * (설계 2026-10-15 §4). {@link #failReplaceWithAfter} 로 도중에 끊을 수 있다 — 그러면 그때까지 바꾼 것만 남는다.
     */
    @Override
    public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다) {
        return Mono.defer(() -> {
            List<DirectoryChanges.UserDiff> 직원변경 = new ArrayList<>();
            List<DirectoryChanges.GroupDiff> 조직변경 = new ArrayList<>();
            List<Runnable> 차례 = new ArrayList<>();
            snapshot.users().values().forEach(user -> {
                DirectoryUser before = users.get(user.id());
                if (!user.equals(before)) {
                    DirectoryChanges.UserDiff diff = new DirectoryChanges.UserDiff(before, user);
                    차례.add(() -> {
                        users.put(user.id(), user);
                        직원변경.add(diff);
                        변경마다.user(diff);
                    });
                }
            });
            snapshot.groups().values().forEach(group -> {
                DirectoryGroup before = groups.get(group.id());
                Set<MemberRef> added = new LinkedHashSet<>(group.members());
                Set<MemberRef> removed = new LinkedHashSet<>();
                if (before != null) {
                    added.removeAll(before.members());
                    removed.addAll(before.members());
                    removed.removeAll(group.members());
                }
                GroupHeader 이전 = before == null ? null : 이름표(before);
                GroupHeader 이후 = 이름표(group);
                if (before == null || !이후.equals(이전) || !added.isEmpty() || !removed.isEmpty()) {
                    DirectoryChanges.GroupDiff diff = new DirectoryChanges.GroupDiff(이전, 이후, added, removed);
                    차례.add(() -> {
                        groups.put(group.id(), group);
                        조직변경.add(diff);
                        변경마다.group(diff);
                    });
                }
            });
            groups.values().stream()
                    .filter(group -> !snapshot.groups().containsKey(group.id()))
                    .forEach(group -> {
                        DirectoryChanges.GroupDiff diff =
                                new DirectoryChanges.GroupDiff(이름표(group), null, Set.of(), group.members());
                        차례.add(() -> {
                            groups.remove(group.id());
                            조직변경.add(diff);
                            변경마다.group(diff);
                        });
                    });
            users.values().stream()
                    .filter(user -> !snapshot.users().containsKey(user.id()))
                    .forEach(user -> {
                        DirectoryChanges.UserDiff diff = new DirectoryChanges.UserDiff(user, null);
                        차례.add(() -> {
                            users.remove(user.id());
                            직원변경.add(diff);
                            변경마다.user(diff);
                        });
                    });

            for (int i = 0; i < 차례.size(); i++) {
                if (i == failReplaceWithAfter) {
                    return Mono.error(new IllegalStateException("replaceWith 도중 실패(테스트)"));
                }
                차례.get(i).run();
            }
            // 다 마쳤으면 조직도를 통째로 맞춘다 — 바뀌지 않은 직원·조직의 순서까지 전과 같게 둔다
            users.clear();
            groups.clear();
            users.putAll(snapshot.users());
            groups.putAll(snapshot.groups());
            // 조직이 사라졌으면 그 조직이 끼인 지정 연결도 사라진다 — 실제 저장소는 사라진 조직을 deleteGroup 으로 지운다(wipe, 설계 2026-11-04 H10)
            assignedParents.entrySet().removeIf(줄 -> !groups.containsKey(줄.getKey()) || !groups.containsKey(줄.getValue()));
            return Mono.just(new DirectoryChanges(직원변경, 조직변경));
        });
    }

    private static GroupHeader 이름표(DirectoryGroup group) {
        return new GroupHeader(group.id(), group.externalId(), group.displayName());
    }

    /** 지정 연결을 (상위, 하위) 쌍으로. */
    private Set<GroupEdge> assignedEdges() {
        Set<GroupEdge> edges = new LinkedHashSet<>();
        assignedParents.forEach((child, parent) -> edges.add(new GroupEdge(parent, child)));
        return edges;
    }

    @Override
    public Mono<DirectorySnapshot> loadAll() {
        return Mono.defer(() -> loadAllFailure != null
                ? Mono.error(loadAllFailure)
                : Mono.just(new DirectorySnapshot(users, groups, assignedEdges())));
    }
}

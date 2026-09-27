package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FakeStateRepository implements DirectoryStateRepository {

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

    /** {@link #findMembers} 가 받은 후보들. 무엇을 물었는지 단언하는 계측이다. */
    public final List<Set<MemberRef>> findMembersCalls = new ArrayList<>();

    /** {@link #findMemberRefs} 가 불린 순서대로의 조직 id. */
    public final List<String> findMemberRefsCalls = new ArrayList<>();

    /** {@link #findChildGroupIds} 가 불린 순서대로의 조직 id. */
    public final List<String> findChildGroupIdsCalls = new ArrayList<>();

    @Override
    public Mono<DirectoryUser> findUser(String userId) {
        return Mono.fromRunnable(() -> findUserCalls.add(userId))
                .then(Mono.justOrEmpty(users.get(userId)));
    }

    @Override
    public Flux<String> findUserIdsByUserName(String userName) {
        if (userName == null) {
            return Flux.empty();
        }
        // 실제 저장소처럼 대소문자를 가리지 않는다(RFC 7643 userName caseExact=false)
        String key = userName.toLowerCase(Locale.ROOT);
        return Flux.fromIterable(users.values())
                .filter(user -> user.userName() != null && key.equals(user.userName().toLowerCase(Locale.ROOT)))
                .map(DirectoryUser::id);
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

    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        users.put(user.id(), user);
        return Mono.empty();
    }

    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        groups.put(group.id(), group);
        return Mono.empty();
    }

    @Override
    public Mono<Void> deleteUser(String userId) {
        users.remove(userId);
        return Mono.empty();
    }

    @Override
    public Mono<Void> deleteGroup(String groupId) {
        groups.remove(groupId);
        return Mono.empty();
    }

    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        return Flux.fromIterable(groups.values())
                .filter(group -> group.members().contains(ref))
                .map(DirectoryGroup::id);
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
    public Flux<String> findChildGroupIds(String groupId) {
        return Flux.defer(() -> {
            findChildGroupIdsCalls.add(groupId);
            return Flux.fromIterable(membersOf(groupId))
                    .filter(ref -> ref.type() == MemberType.GROUP)
                    .map(MemberRef::id);
        });
    }

    @Override
    public Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed) {
        return Mono.fromRunnable(() -> {
            Set<MemberRef> members = new LinkedHashSet<>(membersOf(header.id()));
            members.removeAll(removed);
            members.addAll(added);
            groups.put(header.id(), new DirectoryGroup(header.id(), header.externalId(), header.displayName(), members));
        });
    }

    private Set<MemberRef> membersOf(String groupId) {
        DirectoryGroup group = groups.get(groupId);
        return group == null ? Set.of() : group.members();
    }

    @Override
    public Mono<Void> replaceWith(DirectorySnapshot snapshot) {
        users.clear();
        groups.clear();
        users.putAll(snapshot.users());
        groups.putAll(snapshot.groups());
        return Mono.empty();
    }

    @Override
    public Mono<DirectorySnapshot> loadAll() {
        return Mono.just(new DirectorySnapshot(users, groups));
    }
}

package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FakeStateRepository implements DirectoryStateRepository {

    public final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    public final Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

    /**
     * {@link #findGroupIdsContaining} 이 실제 멤버십과 무관하게 추가로 보고할 조직 id.
     *
     * <p>이 페이크의 {@link #findGroupIdsContaining} 은 {@link #groups} 맵에서 그대로 유도되기
     * 때문에 낡은 GSI 히트를 스스로는 만들 수 없다 — 실제로는 멤버가 이미 빠졌는데
     * (DynamoDB GSI1 이 최종 일관성이라) 역참조가 그 조직을 계속 보고하는 창을 재현하려면
     * 이 필드로 강제로 끼워 넣어야 한다. {@code containsMember} 는 이 필드를 보지 않고
     * {@link #groups} 의 실제 멤버 목록만 본다 — 그래야 "역참조는 낡았는데 멤버 줄 확인은
     * 정확하다"는 시나리오를 표현할 수 있다.
     */
    public final Set<String> staleGroupIdsContaining = new LinkedHashSet<>();

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

    /** {@link #containsMember} 가 불린 순서대로의 (조직 id, 멤버) 쌍. {@link #findGroupCalls} 와 같은 목적. */
    public final List<ContainsMemberCall> containsMemberCalls = new ArrayList<>();

    /** {@link #containsMemberCalls} 한 건의 계측 값. */
    public record ContainsMemberCall(String groupId, MemberRef ref) {
    }

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
        return Flux.fromIterable(users.values())
                .filter(user -> userName.equals(user.userName()))
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
    public Mono<Boolean> containsMember(String groupId, MemberRef ref) {
        return Mono.fromRunnable(() -> containsMemberCalls.add(new ContainsMemberCall(groupId, ref)))
                .then(Mono.defer(() -> {
                    DirectoryGroup group = groups.get(groupId);
                    return Mono.just(group != null && group.members().contains(ref));
                }));
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
        Flux<String> real = Flux.fromIterable(groups.values())
                .filter(group -> group.members().contains(ref))
                .map(DirectoryGroup::id);
        // 낡은 GSI 히트 재현용 — staleGroupIdsContaining 자바독 참고.
        return Flux.concat(real, Flux.fromIterable(staleGroupIdsContaining));
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

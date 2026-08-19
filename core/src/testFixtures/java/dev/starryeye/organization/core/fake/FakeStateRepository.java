package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeStateRepository implements DirectoryStateRepository {

    public final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    public final Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

    /**
     * {@link #findGroup} 이 불린 순서대로의 조직 id. 읽기 횟수 자체를 단언하고 싶은
     * 테스트(요청 단위 캐시가 실제로 먹는지 등)를 위한 계측이며, 그 밖의 동작에는
     * 영향을 주지 않는다.
     */
    public final List<String> findGroupCalls = new ArrayList<>();

    @Override
    public Mono<DirectoryUser> findUser(String userId) {
        return Mono.justOrEmpty(users.get(userId));
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

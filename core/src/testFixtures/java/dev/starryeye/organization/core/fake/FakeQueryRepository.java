package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.query.Page;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@link FakeStateRepository} 의 맵을 그대로 읽는 조회 저장소. 실제 저장소처럼 {@code userName}·조직명은
 * 대소문자를 가리지 않고 소문자 순(같으면 id 순)으로 정렬하며, {@code limit} 을 채우면 끝이어도 다음 위치를
 * 준다(DynamoDB 의 LastEvaluatedKey 와 같다). 위치는 0 부터의 오프셋 문자열이다.
 */
public class FakeQueryRepository implements DirectoryQueryRepository {

    private final FakeStateRepository state;

    /** 불린 메서드와 인자. 책갈피가 먹는지(세기·건너뛰기가 다시 불리지 않는지) 단언하는 데 쓴다. */
    public final List<String> calls = new ArrayList<>();

    public FakeQueryRepository(FakeStateRepository state) {
        this.state = state;
    }

    @Override
    public Flux<DirectoryUser> findUsersByUserName(String userName) {
        calls.add("findUsersByUserName:" + userName);
        return Flux.fromIterable(sortedUsers(false))
                .filter(user -> same(user.userName(), userName));
    }

    @Override
    public Flux<DirectoryUser> findUsersByExternalId(String externalId) {
        calls.add("findUsersByExternalId:" + externalId);
        return Flux.fromIterable(sortedUsers(false))
                .filter(user -> externalId.equals(user.externalId()));
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByDisplayName(String displayName) {
        calls.add("findGroupHeadersByDisplayName:" + displayName);
        return Flux.fromIterable(sortedGroups(false))
                .filter(group -> same(group.displayName(), displayName));
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByExternalId(String externalId) {
        calls.add("findGroupHeadersByExternalId:" + externalId);
        return Flux.fromIterable(sortedGroups(false))
                .filter(group -> externalId.equals(group.externalId()));
    }

    @Override
    public Mono<Page<DirectoryUser>> listUsers(String from, int limit, boolean descending) {
        calls.add("listUsers:" + from + ":" + limit);
        return Mono.just(slice(sortedUsers(descending), from, limit));
    }

    @Override
    public Mono<Page<GroupHeader>> listGroupHeaders(String from, int limit, boolean descending) {
        calls.add("listGroupHeaders:" + from + ":" + limit);
        return Mono.just(slice(sortedGroups(descending), from, limit));
    }

    @Override
    public Mono<Long> countUsers() {
        calls.add("countUsers");
        return Mono.just((long) state.users.size());
    }

    @Override
    public Mono<Long> countGroups() {
        calls.add("countGroups");
        return Mono.just((long) state.groups.size());
    }

    @Override
    public Mono<String> skipUsers(long n, boolean descending) {
        calls.add("skipUsers:" + n);
        return skip(n, state.users.size());
    }

    @Override
    public Mono<String> skipGroups(long n, boolean descending) {
        calls.add("skipGroups:" + n);
        return skip(n, state.groups.size());
    }

    private static Mono<String> skip(long n, int size) {
        if (n <= 0 || size == 0) {
            return Mono.empty();
        }
        return Mono.just(String.valueOf(Math.min(n, size)));
    }

    private static <T> Page<T> slice(List<T> sorted, String from, int limit) {
        int offset = from == null ? 0 : Integer.parseInt(from);
        if (offset >= sorted.size()) {
            return new Page<>(List.of(), null);
        }
        int end = Math.min(offset + limit, sorted.size());
        List<T> items = sorted.subList(offset, end);
        return new Page<>(items, items.size() == limit ? String.valueOf(end) : null);
    }

    private List<DirectoryUser> sortedUsers(boolean descending) {
        List<DirectoryUser> users = new ArrayList<>(state.users.values());
        users.sort(Comparator.comparing((DirectoryUser user) -> lower(user.userName() == null ? user.id() : user.userName()))
                .thenComparing(DirectoryUser::id));
        if (descending) {
            Collections.reverse(users);
        }
        return users;
    }

    private List<GroupHeader> sortedGroups(boolean descending) {
        List<GroupHeader> groups = new ArrayList<>(state.groups.values().stream()
                .map(FakeQueryRepository::header)
                .toList());
        groups.sort(Comparator.comparing((GroupHeader group) -> lower(group.displayName() == null ? group.id() : group.displayName()))
                .thenComparing(GroupHeader::id));
        if (descending) {
            Collections.reverse(groups);
        }
        return groups;
    }

    private static GroupHeader header(DirectoryGroup group) {
        return new GroupHeader(group.id(), group.externalId(), group.displayName());
    }

    private static boolean same(String stored, String asked) {
        return stored != null && lower(stored).equals(lower(asked));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}

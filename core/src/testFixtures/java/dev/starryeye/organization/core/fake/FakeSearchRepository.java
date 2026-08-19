package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 메모리 위에서 접두사 검색과 커서 페이징을 흉내낸다.
 * 커서는 "다음에 읽을 항목의 인덱스" 를 그대로 문자열로 쓴다 — 불투명하다는 계약만 지키면 되고,
 * 테스트에서는 형식이 단순한 편이 낫다.
 */
public class FakeSearchRepository implements DirectorySearchRepository {

    public final List<UserSummary> users = new ArrayList<>();
    public final List<GroupSummary> groups = new ArrayList<>();

    /** 설정되면 모든 검색이 이 예외로 실패한다. 커서 손상 같은 경우를 흉내낸다. */
    public RuntimeException failWith;

    @Override
    public Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit) {
        if (failWith != null) return Mono.error(failWith);
        return Mono.just(page(users, UserSummary::userName, prefix, cursor, limit));
    }

    @Override
    public Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit) {
        if (failWith != null) return Mono.error(failWith);
        // displayName 이 없는 직원은 인덱스에 실리지 않는다 — 실제 GSI 동작과 맞춘다
        List<UserSummary> indexed = users.stream().filter(u -> u.displayName() != null).toList();
        return Mono.just(page(indexed, UserSummary::displayName, prefix, cursor, limit));
    }

    @Override
    public Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit) {
        if (failWith != null) return Mono.error(failWith);
        return Mono.just(page(groups, GroupSummary::displayName, prefix, cursor, limit));
    }

    private <T> Page<T> page(List<T> source, java.util.function.Function<T, String> sortKey,
                             String prefix, String cursor, int limit) {
        List<T> matched = source.stream()
                .filter(item -> sortKey.apply(item) != null && sortKey.apply(item).startsWith(prefix))
                .sorted(Comparator.comparing(sortKey))
                .toList();

        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(from + limit, matched.size());
        String next = to < matched.size() ? String.valueOf(to) : null;
        return new Page<>(matched.subList(from, to), next);
    }
}

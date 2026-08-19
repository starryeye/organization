package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.query.AccessPath;
import dev.starryeye.organization.core.query.EmployeeDetail;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.OrgMember;
import dev.starryeye.organization.core.query.OrganizationDetail;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 관리자 조회. 현재상태(DynamoDB)에서 계층을 재구성하고 OpenFGA 의 실제 판정을 나란히 싣는다.
 *
 * <p><b>왜 둘을 나란히 두나.</b> 현재상태가 요구하는 튜플과 OpenFGA 에 실제로 있는 튜플이
 * 갈릴 수 있는데(SCIM 쓰기 경로에 일부러 미뤄 둔 동시성 결함), 지금 그 어긋남을 알아챌 다른
 * 장치가 없다. 이 화면이 유일한 신호다.
 *
 * <p><b>캐시를 두지 않는다.</b> 계층 깊이가 보통 4~6단이라 요청당 한 자릿수 왕복이면 끝난다.
 * 캐시를 두면 무효화가 새 문제가 되고, 두 앱이 각자 캐시하면 서로 다른 걸 보게 된다.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminQueryUseCase {

    /** 한 직원의 경로 상한. 정상 조직도에서 넘을 일이 없다 — 넘으면 계층이 비정상이거나 버그다. */
    public static final int MAX_PATHS = 200;

    private static final int LOAD_CONCURRENCY = 8;
    private static final int CHECK_CONCURRENCY = 8;

    private final DirectoryStateRepository state;
    private final DirectorySearchRepository search;
    private final RelationTupleChecker checker;

    public Mono<Page<UserSummary>> searchEmployeesByUserName(String prefix, String cursor, int limit) {
        return search.searchUsersByUserName(prefix, cursor, limit);
    }

    public Mono<Page<UserSummary>> searchEmployeesByDisplayName(String prefix, String cursor, int limit) {
        return search.searchUsersByDisplayName(prefix, cursor, limit);
    }

    public Mono<Page<GroupSummary>> searchOrganizations(String prefix, String cursor, int limit) {
        return search.searchGroupsByDisplayName(prefix, cursor, limit);
    }

    // ---------- 직원 상세 ----------

    public Mono<EmployeeDetail> employeeDetail(String employeeId) {
        return state.findUser(employeeId)
                .flatMap(user -> climb(employeeId).flatMap(reached -> toDetail(user, reached)));
    }

    /**
     * 롤업까지 한 번에 묻는 튜플. relation 이 {@code direct_member} 가 아니라 {@code member} 다 —
     * {@code member} 는 {@code direct_member or member from child} 로 정의돼 있어 직속이든
     * 상위든 한 번의 Check 로 답이 나온다. {@code direct_member} 로 물으면 조상 조직에 대해서는
     * 항상 false 가 나와 롤업 경로가 전부 드리프트로 보인다. {@code RelationTuple} 에 이 팩토리가
     * 없어 생성자를 직접 쓴다.
     */
    private static RelationTuple memberOf(String employeeId, String orgCode) {
        return new RelationTuple("user:" + employeeId, "member", "group:" + orgCode);
    }

    /** 직원이 직접 멤버로 등록된 조직들. 레코드가 없는 참조는 건너뛴다. */
    private Mono<List<DirectoryGroup>> directGroupsOf(String employeeId) {
        return state.findGroupIdsContaining(MemberRef.user(employeeId))
                .flatMap(this::loadGroupOrEmpty, LOAD_CONCURRENCY)
                .collectList();
    }

    private Mono<DirectoryGroup> loadGroupOrEmpty(String groupId) {
        return state.findGroup(groupId)
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("조직 '{}' 이 멤버십에서 참조되지만 레코드가 없어 건너뛴다", groupId)));
    }

    /**
     * 직속 조직들에서 시작해 상위로 끝까지 올라간다.
     *
     * <p>방문 집합은 무한 루프 방지에 필수이고, 이미 본 조직에 다시 닿으면 그것이 곧 순환이다.
     * 그때 조용히 멈추지 않고 표시해 올린다 — 관리 도구에서 순환은 숨길 사실이 아니다.
     *
     * <p><b>재귀로 레벨마다 새 Mono 체인을 쌓지 않는다.</b> 계층 하나마다 재귀 호출로
     * {@code flatMap} 을 중첩하면, 동기 소스(테스트의 페이크뿐 아니라 이미 캐시된 응답 등)에서는
     * 그 flatMap 콜백이 같은 스레드에서 곧바로 실행되어 계층 깊이만큼 자바 콜스택이 쌓인다 —
     * 깊은 사슬에서는 자체 예산(MAX_PATHS) 검사가 걸리기도 전에 StackOverflowError 가 날 수
     * 있다. 대신 {@link Flux#expand} 에 너비 우선 확장을 맡긴다 — 내부적으로 반복 처리되어
     * 깊이가 스택을 쓰지 않는다({@link IncrementalSyncUseCase#reaches} 와 같은 이유로 같은
     * 해법을 쓴다).
     */
    private Mono<Reached> climb(String employeeId) {
        Reached reached = new Reached();
        return directGroupsOf(employeeId)
                .flatMap(direct -> Flux.fromIterable(seedDirect(direct, reached))
                        .expand(current -> expandParents(current, reached))
                        .then(Mono.just(reached)));
    }

    /** 직속 조직들을 방문 집합에 넣는다. 상한은 직속 단계에서부터 적용한다. */
    private List<DirectoryGroup> seedDirect(List<DirectoryGroup> direct, Reached reached) {
        List<DirectoryGroup> seeded = new ArrayList<>();
        for (DirectoryGroup group : direct) {
            if (reached.entries.size() >= MAX_PATHS) {
                reached.truncated = true;
                break;
            }
            reached.add(group, AccessPath.DIRECT, false);
            seeded.add(group);
        }
        return seeded;
    }

    /** {@code current} 의 상위 조직들을 찾아, 새로 닿은 것만 골라 계속 확장한다. */
    private Flux<DirectoryGroup> expandParents(DirectoryGroup current, Reached reached) {
        if (reached.truncated) {
            return Flux.empty();
        }
        return state.findGroupIdsContaining(MemberRef.group(current.id()))
                .concatMap(this::loadGroupOrEmpty)
                .filter(parent -> acceptParent(parent, reached));
    }

    /**
     * 새로 닿은 상위 조직이면 방문 집합에 더하고 계속 올라갈 후보로 승인한다. 이미 본
     * 조직이면 순환으로 표시하고 더 올라가지 않는다. 상한에 닿으면 자르고 더 올라가지 않는다.
     */
    private boolean acceptParent(DirectoryGroup parent, Reached reached) {
        if (reached.seen.contains(parent.id())) {
            reached.markCycle(parent.id());
            return false;
        }
        if (reached.entries.size() >= MAX_PATHS) {
            reached.truncated = true;
            return false;
        }
        reached.add(parent, AccessPath.ROLLUP, false);
        return true;
    }

    private Mono<EmployeeDetail> toDetail(DirectoryUser user, Reached reached) {
        return Flux.fromIterable(reached.entries)
                .flatMap(entry -> checkOrNull(memberOf(user.id(), entry.group.id()))
                                .map(allowed -> new AccessPath(entry.group.id(), entry.group.displayName(),
                                        entry.via, user.active(), allowed, entry.cycle))
                                .defaultIfEmpty(new AccessPath(entry.group.id(), entry.group.displayName(),
                                        entry.via, user.active(), null, entry.cycle)),
                        CHECK_CONCURRENCY)
                .collectList()
                .map(paths -> new EmployeeDetail(user.id(), user.userName(), user.displayName(),
                        user.email(), user.active(), paths, reached.truncated));
    }

    // ---------- 조직 상세 ----------

    public Mono<OrganizationDetail> organizationDetail(String orgCode, int memberPageSize) {
        return state.findGroup(orgCode).flatMap(group ->
                Mono.zip(ancestorsOf(group), childrenOf(group),
                                membersPage(group, null, memberPageSize))
                        .map(parts -> new OrganizationDetail(
                                group.id(), group.displayName(), group.externalId(),
                                parts.getT1(), parts.getT2(), parts.getT3())));
    }

    public Mono<Page<OrgMember>> organizationMembers(String orgCode, String cursor, int limit) {
        return state.findGroup(orgCode).flatMap(group -> membersPage(group, cursor, limit));
    }

    /**
     * 상위 계층 전부. {@code group} 자신은 방문 집합에 미리 넣어 두어, 순환이 자기 자신으로
     * 되돌아오더라도 조상 목록에 자신이 끼어들지 않는다.
     *
     * <p>파생 목록은 순회가 끝난 뒤 {@code reached.entries}(부작용으로 누적된 상태)를 읽어
     * 만들어야 한다 — {@code Mono.just(...)} 처럼 조립 시점에 즉시 계산해 버리면 아직 아무것도
     * 채워지지 않은 빈 목록을 캡처하는 함정에 빠진다. {@code Mono.fromSupplier} 로 지연시킨다.
     */
    private Mono<List<GroupSummary>> ancestorsOf(DirectoryGroup group) {
        Reached reached = new Reached();
        reached.seen.add(group.id());
        return Flux.just(group)
                .expand(current -> expandParents(current, reached))
                .then(Mono.fromSupplier(() -> reached.entries.stream()
                        .map(entry -> new GroupSummary(entry.group.id(), entry.group.displayName()))
                        .toList()));
    }

    /**
     * 직속 하위 조직만(1 depth). 멤버 참조에는 조직코드밖에 없으므로 표시명을 채우려면
     * 각 하위 조직을 읽어야 한다 — 코드만 담아 돌려주면 관리 화면의 이름 칸이 비어버린다.
     * 하위 조직은 보통 수십 개라 이 정도 읽기는 감당된다.
     */
    private Mono<List<GroupSummary>> childrenOf(DirectoryGroup group) {
        return Flux.fromIterable(group.members())
                .filter(member -> member.type() == MemberType.GROUP)
                .map(MemberRef::id)
                .sort()
                .concatMap(this::loadGroupOrEmpty)
                .map(child -> new GroupSummary(child.id(), child.displayName()))
                .collectList();
    }

    private Mono<Page<OrgMember>> membersPage(DirectoryGroup group, String cursor, int limit) {
        List<String> userIds = group.members().stream()
                .filter(member -> member.type() == MemberType.USER)
                .map(MemberRef::id)
                .sorted()
                .toList();

        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(from + limit, userIds.size());
        String next = to < userIds.size() ? String.valueOf(to) : null;

        return Flux.fromIterable(userIds.subList(from, to))
                .concatMap(userId -> state.findUser(userId)
                        .flatMap(user -> checkOrNull(memberOf(user.id(), group.id()))
                                .map(allowed -> new OrgMember(user.id(), user.displayName(),
                                        user.active(), allowed))
                                .defaultIfEmpty(new OrgMember(user.id(), user.displayName(),
                                        user.active(), null))))
                .collectList()
                .map(items -> new Page<>(items, next));
    }

    // ---------- Check ----------

    /**
     * Check 를 부르되 실패는 빈 신호로 바꾼다. 호출자가 그것을 null 로 채운다.
     *
     * <p>반드시 <b>항목 단위</b>로 감싸야 한다. 스트림 전체에 {@code onErrorResume} 을 걸면
     * 첫 실패에서 나머지 항목이 통째로 사라진다.
     */
    private Mono<Boolean> checkOrNull(RelationTuple tuple) {
        return checker.check(tuple)
                .onErrorResume(error -> {
                    log.warn("Check 실패 — 판정을 보류한다. tuple={}", tuple, error);
                    return Mono.empty();
                });
    }

    // ---------- 순회 상태 ----------

    /**
     * 한 번의 순회(직원 하나의 계층 순회, 또는 조직 하나의 조상 순회)가 공유하는 작업 공간.
     * {@link Flux#expand} 의 여러 분기가 이 인스턴스를 함께 참조하지만, Reactive Streams
     * 규격상 한 Subscriber 에 대한 신호는 직렬화되어 도달하므로(동시 onNext 없음) 평범한
     * {@link ArrayList}/{@link LinkedHashSet} 로도 안전하다 — {@link IncrementalSyncUseCase}
     * 의 {@code CycleScan}/{@code visited} 와 같은 전제다.
     */
    private static final class Reached {
        private final Set<String> seen = new LinkedHashSet<>();
        private final List<Entry> entries = new ArrayList<>();
        private boolean truncated;

        void add(DirectoryGroup group, String via, boolean cycle) {
            if (seen.add(group.id())) {
                entries.add(new Entry(group, via, cycle));
            }
        }

        void markCycle(String groupId) {
            entries.stream()
                    .filter(entry -> entry.group.id().equals(groupId))
                    .forEach(entry -> entry.cycle = true);
        }
    }

    private static final class Entry {
        private final DirectoryGroup group;
        private final String via;
        private boolean cycle;

        Entry(DirectoryGroup group, String via, boolean cycle) {
            this.group = group;
            this.via = via;
            this.cycle = cycle;
        }
    }
}

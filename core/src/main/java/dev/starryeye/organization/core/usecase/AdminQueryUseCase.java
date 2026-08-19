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

    /**
     * 직원이 직접 멤버로 등록된 조직들. 레코드가 없는 참조는 건너뛴다.
     *
     * <p>id 소스 자체를 {@code MAX_PATHS + 1} 로 자른다. 상한은 결과 개수뿐 아니라 <b>일하는
     * 양</b>도 지켜야 한다 — 이 자르기가 없으면 상한을 훨씬 넘는 소속을 가진 직원 하나가
     * {@code findGroupSummary} 왕복을 소속 개수만큼 전부 치르고 나서야 잘린다.
     * {@code seedDirect} 는 모인 목록이 {@code MAX_PATHS} 를 넘는지로 "더 있었다" 를 판단해
     * {@code truncated} 를 세운다.
     */
    private Mono<List<GroupSummary>> directGroupsOf(String employeeId) {
        return state.findGroupIdsContaining(MemberRef.user(employeeId))
                .take(MAX_PATHS + 1)
                .flatMap(this::loadGroupOrEmpty, LOAD_CONCURRENCY)
                .collectList();
    }

    /**
     * 순회에 필요한 두 칸({@code orgCode}, {@code displayName})만 읽는다. 레코드가 없는
     * 참조는 건너뛴다.
     *
     * <p><b>{@code state.findGroup} 을 쓰지 않는다.</b> 그쪽은 {@code GROUP#<code>} 파티션을
     * 통째로 훑어 멤버십 아이템까지 전부 읽는데, 순회는 이름표 두 칸만 쓴다. 하위 조직 30개
     * 각각이 멤버 500명이면 이름 칸 30개를 채우려고 15,000 아이템을 읽던 자리다. 두 엔드포인트
     * 모두 인증이 없어 그 증폭을 익명 호출자가 조종할 수 있었다.
     */
    private Mono<GroupSummary> loadGroupOrEmpty(String groupId) {
        return search.findGroupSummary(groupId)
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("조직 '{}' 이 멤버십에서 참조되지만 레코드가 없어 건너뛴다", groupId)));
    }

    /**
     * 참조된 직원 레코드를 읽는다. 없으면 건너뛰되 경고를 남긴다 — 존재하지 않는 직원을
     * 가리키는 멤버십은 소음이 아니라 이 화면이 잡아내야 할 어긋남 그 자체다.
     */
    private Mono<DirectoryUser> loadUserOrEmpty(String userId) {
        return state.findUser(userId)
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("직원 '{}' 이 멤버십에서 참조되지만 레코드가 없어 건너뛴다", userId)));
    }

    /**
     * 직속 조직들에서 시작해 상위로 끝까지 올라간다.
     *
     * <p>방문 집합은 무한 루프 방지에 필수이고, <b>지금 밟고 있는 경로 위에서</b> 이미 본
     * 조직에 다시 닿으면 그것이 곧 순환이다({@link #acceptParent} 참고). 그때 조용히 멈추지
     * 않고 표시해 올린다 — 관리 도구에서 순환은 숨길 사실이 아니다.
     *
     * <p><b>재귀로 레벨마다 새 Mono 체인을 쌓지 않는다.</b> 계층 하나마다 재귀 호출로
     * {@code flatMap} 을 중첩하면, 동기 소스(테스트의 페이크뿐 아니라 이미 캐시된 응답 등)에서는
     * 그 flatMap 콜백이 같은 스레드에서 곧바로 실행되어 계층 깊이만큼 자바 콜스택이 쌓인다 —
     * 깊은 사슬에서는 자체 예산(MAX_PATHS) 검사가 걸리기도 전에 StackOverflowError 가 날 수
     * 있다. 대신 {@link Flux#expand} 에 너비 우선 확장을 맡긴다 — 내부적으로 반복 처리되어
     * 깊이가 스택을 쓰지 않는다. {@code IncrementalSyncUseCase.reaches()} 가 순환 검사에 같은
     * 연산자를 쓰는 것과 같은 이유다.
     */
    private Mono<Reached> climb(String employeeId) {
        Reached reached = new Reached();
        return directGroupsOf(employeeId)
                .flatMap(direct -> Flux.fromIterable(seedDirect(direct, reached))
                        .expand(step -> expandParents(step, reached))
                        .then(Mono.just(reached)));
    }

    /**
     * 지금 밟고 있는 경로 위의 조직 id 들과 함께 다니는 확장 단위. 순환과 다이아몬드를
     * 구분하려면 "전역으로 이미 봤는가" 만으로는 부족하고 "지금 이 경로 위에서 봤는가" 가
     * 필요하다 — {@link #acceptParent} 참고.
     */
    private record Step(GroupSummary group, Set<String> path) {
    }

    /** 직속 조직들을 방문 집합에 넣는다. 상한은 직속 단계에서부터 적용한다. */
    private List<Step> seedDirect(List<GroupSummary> direct, Reached reached) {
        List<Step> seeded = new ArrayList<>();
        for (GroupSummary group : direct) {
            if (reached.entries.size() >= MAX_PATHS) {
                reached.truncated = true;
                break;
            }
            if (reached.add(group, AccessPath.DIRECT, false)) {
                seeded.add(new Step(group, Set.of(group.orgCode())));
            }
        }
        return seeded;
    }

    /** {@code step} 의 상위 조직들을 찾아, 계속 올라갈 다음 {@link Step} 만 골라 낸다. */
    private Flux<Step> expandParents(Step step, Reached reached) {
        if (reached.truncated) {
            return Flux.empty();
        }
        // acceptParent 는 순환/다이아몬드/상한을 만나면 null 을 돌려준다. Flux#map 은 null 을
        // 허용하지 않으므로(NullPointerException) flatMap + Mono.justOrEmpty 로 흡수한다.
        return state.findGroupIdsContaining(MemberRef.group(step.group().orgCode()))
                .concatMap(this::loadGroupOrEmpty)
                .flatMap(parent -> Mono.justOrEmpty(acceptParent(parent, step.path(), reached)));
    }

    /**
     * 새로 닿은 상위 조직이면 방문 집합에 더하고 다음 {@link Step} 을 돌려준다.
     *
     * <p><b>순환과 다이아몬드는 다르다.</b> {@code path}(지금 밟고 있는 경로) 안에 이미 있는
     * 조직에 다시 닿으면 그건 자기 조상으로 되돌아온 것 — 진짜 순환이라 표시하고 더 올라가지
     * 않는다. 반면 {@code reached.seen}(전역 방문 집합)에는 있지만 {@code path} 에는 없는
     * 조직이면, 다른 갈래에서 이미 닿았던 것뿐이다(다이아몬드: 두 하위 조직이 같은 상위를
     * 공유하는 흔한 모양, 또는 직원이 어떤 조직과 그 조상에 동시에 직속으로 속한 경우). 그건
     * 순환이 아니므로 표시하지 않되, 같은 조직을 두 번 확장하는 낭비를 막기 위해 더 올라가지도
     * 않는다 — 그 갈래는 먼저 닿은 쪽이 이미 끝까지 확장했거나 확장 중이다.
     */
    private Step acceptParent(GroupSummary parent, Set<String> path, Reached reached) {
        if (path.contains(parent.orgCode())) {
            reached.markCycle(parent.orgCode());
            return null;
        }
        if (reached.seen.contains(parent.orgCode())) {
            return null; // 다이아몬드 — 순환이 아니며 다시 확장하지 않는다
        }
        if (reached.entries.size() >= MAX_PATHS) {
            reached.truncated = true;
            return null;
        }
        reached.add(parent, AccessPath.ROLLUP, false);
        Set<String> nextPath = new LinkedHashSet<>(path);
        nextPath.add(parent.orgCode());
        return new Step(parent, Set.copyOf(nextPath));
    }

    private Mono<EmployeeDetail> toDetail(DirectoryUser user, Reached reached) {
        return Flux.fromIterable(reached.entries)
                .flatMap(entry -> checkOrNull(memberOf(user.id(), entry.group.orgCode()))
                                .map(allowed -> new AccessPath(entry.group.orgCode(), entry.group.displayName(),
                                        entry.via, user.active(), allowed, entry.cycle))
                                .defaultIfEmpty(new AccessPath(entry.group.orgCode(), entry.group.displayName(),
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
     *
     * <p>{@code OrganizationDetail} 에는 {@code truncated} 를 실을 자리가 없다(이 태스크는
     * {@code core} 의 기존 파일을 건드릴 수 없어 필드를 더할 수도 없다). 그래서 잘렸을 때는
     * 조용히 짧은 목록을 돌려주는 대신 경고를 남긴다.
     */
    private Mono<List<GroupSummary>> ancestorsOf(DirectoryGroup group) {
        Reached reached = new Reached();
        reached.seen.add(group.id());
        Step seed = new Step(new GroupSummary(group.id(), group.displayName()), Set.of(group.id()));
        return Flux.just(seed)
                .expand(step -> expandParents(step, reached))
                .then(Mono.fromSupplier(() -> {
                    if (reached.truncated) {
                        log.warn("조직 '{}' 의 상위 계층이 상한({})을 넘어 잘렸습니다", group.id(), MAX_PATHS);
                    }
                    return reached.entries.stream().map(entry -> entry.group).toList();
                }));
    }

    /**
     * 직속 하위 조직만(1 depth). 멤버 참조에는 조직코드밖에 없으므로 표시명을 채우려면
     * 각 하위 조직을 읽어야 한다 — 코드만 담아 돌려주면 관리 화면의 이름 칸이 비어버린다.
     * 이름표 한 줄씩만 읽으므로({@link #loadGroupOrEmpty}) 하위 조직 수만큼의 GetItem 이다.
     */
    private Mono<List<GroupSummary>> childrenOf(DirectoryGroup group) {
        return Flux.fromIterable(group.members())
                .filter(member -> member.type() == MemberType.GROUP)
                .map(MemberRef::id)
                .sort()
                .concatMap(this::loadGroupOrEmpty)
                .collectList();
    }

    private Mono<Page<OrgMember>> membersPage(DirectoryGroup group, String cursor, int limit) {
        List<String> userIds = group.members().stream()
                .filter(member -> member.type() == MemberType.USER)
                .map(MemberRef::id)
                .sorted()
                .toList();

        int from = parseCursor(cursor, userIds.size());
        int to = Math.min(from + limit, userIds.size());
        String next = to < userIds.size() ? String.valueOf(to) : null;

        return Flux.fromIterable(userIds.subList(from, to))
                .concatMap(userId -> loadUserOrEmpty(userId)
                        .flatMap(user -> checkOrNull(memberOf(user.id(), group.id()))
                                .map(allowed -> new OrgMember(user.id(), user.displayName(),
                                        user.active(), allowed))
                                .defaultIfEmpty(new OrgMember(user.id(), user.displayName(),
                                        user.active(), null))))
                .collectList()
                .map(items -> new Page<>(items, next));
    }

    /**
     * 커서를 신뢰하지 않고 유효 범위로 접는다. 멤버가 지워진 뒤 재발급된 낡은 커서는
     * 드문 일이 아니다 — {@code cursor="10"} 인데 멤버가 3명뿐이면 예외 대신 빈 마지막
     * 페이지를 주고, 음수도 0 으로 접는다. 다만 아예 숫자가 아닌 커서는 호출자의 실수이므로
     * 조용히 접지 않고 예외를 던진다 — 뒤이을 컨트롤러 계층이 이를 400 으로 매핑한다.
     */
    private static int parseCursor(String cursor, int size) {
        if (cursor == null) {
            return 0;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("잘못된 커서: " + cursor, e);
        }
        return Math.max(0, Math.min(parsed, size));
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

        /** 새로 방문한 조직이면 더하고 {@code true} 를 돌려준다. 이미 있던 조직이면 {@code false}. */
        boolean add(GroupSummary group, String via, boolean cycle) {
            if (seen.add(group.orgCode())) {
                entries.add(new Entry(group, via, cycle));
                return true;
            }
            return false;
        }

        void markCycle(String groupId) {
            entries.stream()
                    .filter(entry -> entry.group.orgCode().equals(groupId))
                    .forEach(entry -> entry.cycle = true);
        }
    }

    private static final class Entry {
        private final GroupSummary group;
        private final String via;
        private boolean cycle;

        Entry(GroupSummary group, String via, boolean cycle) {
            this.group = group;
            this.via = via;
            this.cycle = cycle;
        }
    }
}

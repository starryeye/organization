package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 생성된 조직도 하나. <b>이것이 진실이고</b> LDIF·SCIM 렌더러가 각자의 형식으로 뽑아낸다.
 *
 * <p>같은 조직도에서 두 입력이 나오므로 {@link #snapshot()} 이 곧 <b>기대 상태</b>가 되고,
 * 두 커넥터가 같은 결과에 도달하는지 대조하는 것도 공짜로 된다.
 *
 * <p><b>{@code externalId} 는 비워 둔다.</b> LDAP 은 DN 을, SCIM 은 그쪽 식별자를 채우므로
 * 조직도 자체는 그것을 모른다. 검증에서도 비교하지 않는다 — 이 값을 읽는 코드가 없기
 * 때문이다(follow-ups §4).
 *
 * <p><b>{@link #지워진멤버십} 은 이 조직도에 이르기까지 사라진 멤버십이다.</b> 하네스는 조직도에
 * 있는 멤버십만 묻기 때문에, 이것이 없으면 "지웠어야 할 권한이 남았는가" 를 못 묻는다.
 * {@link OrgChartEditor#완성()} 이 편집 전후를 비교해 쌓는다. 2인자 생성자는 기억이 없는
 * 최초 조직도용이다.
 */
public record OrgChart(DirectorySnapshot snapshot, Landmarks landmarks, Set<Membership> 지워진멤버십) {

    public OrgChart {
        지워진멤버십 = 지워진멤버십 == null ? Set.of() : Set.copyOf(지워진멤버십);
    }

    /** 기억이 없는 최초 조직도. */
    public OrgChart(DirectorySnapshot snapshot, Landmarks landmarks) {
        this(snapshot, landmarks, Set.of());
    }

    /** 지금 조직도의 멤버십 전부. 에디터가 편집 전후를 비교하는 데 쓴다. */
    public Set<Membership> 멤버십들() {
        Set<Membership> all = new HashSet<>();
        for (DirectoryGroup group : snapshot.groups().values()) {
            for (MemberRef member : group.members()) {
                all.add(new Membership(group.id(), member));
            }
        }
        return all;
    }

    /** 조직 {@code orgCode} 의 조상들을 가까운 순으로. 롤업 검증이 이 체인을 탄다. */
    public List<String> 조상들(String orgCode) {
        List<String> chain = new ArrayList<>();
        String current = orgCode;
        while (true) {
            String parent = 부모(current);
            if (parent == null) {
                return chain;
            }
            chain.add(parent);
            current = parent;
        }
    }

    /** {@code orgCode} 를 자식으로 갖는 조직. 없으면 {@code null} (루트이거나 고아). */
    public String 부모(String orgCode) {
        for (DirectoryGroup group : snapshot.groups().values()) {
            if (group.members().contains(MemberRef.group(orgCode))) {
                return group.id();
            }
        }
        return null;
    }

    /**
     * {@code userId} 의 직속 조직 <b>하나</b>. 둘 이상이면 깨진다.
     *
     * <p>{@link #직속조직들} 에서 아무거나 하나 집는 코드를 막으려고 있다.
     * {@link DirectorySnapshot} 은 {@code Map.copyOf} 로 굳으므로 순회 순서가 <b>JVM 실행마다
     * 달라진다</b>. 겸직 직원에게 "첫 번째 조직" 같은 것은 없고, 그것에 기대는 검증은 어느 날
     * 갑자기 다른 답을 낸다.
     */
    public String 직속조직(String userId) {
        Set<String> orgs = 직속조직들(userId);
        if (orgs.size() != 1) {
            throw new IllegalArgumentException(
                    "직속 조직이 하나가 아닙니다: " + userId + " → " + orgs);
        }
        return orgs.iterator().next();
    }

    /**
     * 겸직이 있어도 <b>하나로 정해지는</b> 주 소속. 아이디 정렬 순 첫 번째다.
     *
     * <p>DIT 처럼 소속을 하나만 표현할 수 있는 형식이 이것을 쓴다. 정렬로 정하는 이유는
     * {@link DirectorySnapshot} 의 순회 순서가 JVM 실행마다 달라지기 때문이다 — "첫 번째"
     * 를 순회로 정하면 렌더러가 실행마다 다른 조직에 직원을 심는다.
     */
    public String 주소속(String userId) {
        return 직속조직들(userId).stream().sorted().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("소속이 없는 직원입니다: " + userId));
    }

    /** {@code userId} 가 직속으로 속한 조직들. */
    public Set<String> 직속조직들(String userId) {
        Set<String> orgs = new LinkedHashSet<>();
        for (DirectoryGroup group : snapshot.groups().values()) {
            if (group.members().contains(MemberRef.user(userId))) {
                orgs.add(group.id());
            }
        }
        return orgs;
    }

    /** 이 직원이 {@code member} 로 성립해야 하는 조직 전부 — 직속 + 그 조상들. */
    public Set<String> 기대소속(String userId) {
        Set<String> all = new LinkedHashSet<>();
        for (String org : 직속조직들(userId)) {
            all.add(org);
            all.addAll(조상들(org));
        }
        return all;
    }

    /**
     * {@code orgCode} 아래 모든 하위 조직. 롤업 <b>음성</b> 검증이 이것을 쓴다.
     *
     * <p>멤버십은 위로만 흐르므로, 어떤 조직의 직속 직원은 그 조직의 <b>자손</b>에 대해
     * {@code member = false} 여야 한다. 아래로 새는 결함은 양성 검증만으로는 절대 안 잡힌다 —
     * 있어야 할 것은 그대로 다 있기 때문이다.
     */
    public Set<String> 자손들(String orgCode) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> 남은것 = new ArrayDeque<>(자식조직들(orgCode));
        while (!남은것.isEmpty()) {
            String current = 남은것.pop();
            if (found.add(current)) {
                남은것.addAll(자식조직들(current));
            }
        }
        return found;
    }

    /** {@code orgCode} 의 직속 하위 조직들. */
    public Set<String> 자식조직들(String orgCode) {
        DirectoryGroup group = snapshot.groups().get(orgCode);
        if (group == null) {
            return Set.of();
        }
        Set<String> children = new LinkedHashSet<>();
        for (MemberRef member : group.members()) {
            if (member.type() == MemberType.GROUP) {
                children.add(member.id());
            }
        }
        return children;
    }

    public long 멤버십수() {
        return snapshot.groups().values().stream()
                .flatMap(group -> group.members().stream())
                .filter(member -> member.type() == MemberType.USER)
                .count();
    }

    public long child간선수() {
        return snapshot.groups().values().stream()
                .flatMap(group -> group.members().stream())
                .filter(member -> member.type() == MemberType.GROUP)
                .count();
    }
}

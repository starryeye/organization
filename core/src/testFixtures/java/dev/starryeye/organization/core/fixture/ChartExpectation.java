package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 조직도가 OpenFGA 에 요구하는 것 — 하네스({@link SyncVerifier})와 프로브({@code OpenFgaProbe})가
 * 함께 쓰는 <b>하나의 기대값</b> (스펙 §4).
 *
 * <p><b>운영의 {@code TupleMapper} 를 쓰지 않는다. 합치지도 말 것.</b> 하네스가 운영 코드에게 정답을
 * 물으면 운영이 틀릴 때 정답도 같이 틀려 검증이 통과한다. 둘이 같은 규칙을 따른다는 사실은
 * {@code ChartExpectationAgreementTest} 한 곳에서만 확인한다.
 *
 * <p><b>끊긴 참조</b>(멤버 목록이 조직도에 없는 직원·조직을 가리킴)는 {@link #of} 가 거부한다. 운영에서는
 * 정당한 상태(SCIM 에서 조직이 먼저 도착)지만 기대 조직도에서는 대개 시나리오 버그다. 일부러 만드는
 * 시나리오만 {@link #끊긴참조를_허용하며} 로 선언한다. 허용하면 튜플을 기대하지 않되 후보로 넣어
 * "없어야 함" 을 묻는다.
 *
 * <p><b>순환</b>은 항상 거부한다. 어느 간선을 버릴지는 운영 구현의 세부라 기대값이 흉내 내면
 * {@code TupleMapper} 를 다시 베끼는 것이 된다. 순환 시나리오는 필요한 튜플을 직접 Check 한다.
 */
public final class ChartExpectation {

    private static final Comparator<MemberRef> 멤버순 =
            Comparator.comparing(MemberRef::type).thenComparing(MemberRef::id);

    private final OrgChart chart;
    /** 하위 조직 → 그 조직을 하위로 가진 조직들. 조직도에 있는 조직끼리만. */
    private final Map<String, Set<String>> 부모들;
    private final Set<RelationTuple> 있어야할튜플;
    private final Set<RelationTuple> 물어볼후보;

    private ChartExpectation(OrgChart chart, boolean 끊긴참조허용) {
        this.chart = chart;
        DirectorySnapshot snapshot = chart.snapshot();
        if (!끊긴참조허용) {
            List<String> 끊긴것 = 끊긴참조들(snapshot);
            if (!끊긴것.isEmpty()) {
                throw new IllegalArgumentException(
                        "조직도에 끊긴 참조가 있습니다 — 의도한 것이면 ChartExpectation.끊긴참조를_허용하며 로 "
                                + "선언하세요: " + 끊긴것);
            }
        }
        순환이_없어야_한다(snapshot);
        this.부모들 = 부모들을_모은다(snapshot);
        this.있어야할튜플 = 있어야할튜플을_모은다(snapshot);
        this.물어볼후보 = 후보를_모은다(chart);
    }

    public static ChartExpectation of(OrgChart chart) {
        return new ChartExpectation(chart, false);
    }

    public static ChartExpectation 끊긴참조를_허용하며(OrgChart chart) {
        return new ChartExpectation(chart, true);
    }

    public OrgChart chart() {
        return chart;
    }

    /** 활성 직원의 소속마다 {@code direct_member}, 조직도에 있는 하위 조직마다 {@code child}. */
    public Set<RelationTuple> 있어야할튜플() {
        return 있어야할튜플;
    }

    /**
     * OpenFGA 에 물어볼 것. 조직도의 <b>모든</b> 멤버십(존재·활성 무관) + 지워진 멤버십.
     * 여기서 {@link #있어야할튜플} 을 뺀 것은 전부 없어야 한다.
     */
    public Set<RelationTuple> 물어볼후보() {
        return 물어볼후보;
    }

    /** 활성 직원이면 직속 조직과 모든 조상에 대해 {@code member}. 아니면 없다. */
    public Set<RelationTuple> 롤업양성(String userId) {
        if (!활성인가(userId)) {
            return Set.of();
        }
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        기대소속(userId).forEach(org -> tuples.add(RelationTuple.member(userId, org)));
        return tuples;
    }

    /**
     * 이 직원이 {@code member} 면 안 되는 조직들. 직속 조직의 <b>자손</b>과 직속 조직의 부모가 가진
     * <b>형제 가지</b>, 기대소속은 뺀다. 비활성이면 기대소속도 음성이다 — 멤버십은 남기고 튜플만 지우는
     * 것이 비활성의 정의다 (설계 §5.1).
     */
    public Set<RelationTuple> 롤업음성(String userId) {
        if (!chart.snapshot().users().containsKey(userId)) {
            return Set.of();
        }
        Set<String> 기대소속 = 기대소속(userId);
        Set<String> 음성 = new TreeSet<>();
        for (String 직속 : 직속조직들(userId)) {
            음성.addAll(자손들(직속));
            for (String 부모 : 부모들.getOrDefault(직속, Set.of())) {
                음성.addAll(자식조직들(부모));
            }
        }
        음성.removeAll(기대소속);
        if (!활성인가(userId)) {
            음성.addAll(기대소속);
        }
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        음성.forEach(org -> tuples.add(RelationTuple.member(userId, org)));
        return tuples;
    }

    // ---------- 조직도 읽기 ----------

    private boolean 활성인가(String userId) {
        DirectoryUser user = chart.snapshot().users().get(userId);
        return user != null && user.active();
    }

    private Set<String> 직속조직들(String userId) {
        Set<String> orgs = new TreeSet<>();
        for (DirectoryGroup group : chart.snapshot().groups().values()) {
            if (group.members().contains(MemberRef.user(userId))) {
                orgs.add(group.id());
            }
        }
        return orgs;
    }

    /** 직속 + <b>모든 부모</b>를 따라 올라간 조상. {@link OrgChart#부모} 는 첫 부모만 주므로 쓰지 않는다. */
    private Set<String> 기대소속(String userId) {
        Set<String> found = new TreeSet<>();
        Deque<String> 남은것 = new ArrayDeque<>(직속조직들(userId));
        while (!남은것.isEmpty()) {
            String current = 남은것.pop();
            if (found.add(current)) {
                남은것.addAll(부모들.getOrDefault(current, Set.of()));
            }
        }
        return found;
    }

    private Set<String> 자손들(String orgCode) {
        Set<String> found = new TreeSet<>();
        Deque<String> 남은것 = new ArrayDeque<>(자식조직들(orgCode));
        while (!남은것.isEmpty()) {
            String current = 남은것.pop();
            if (found.add(current)) {
                남은것.addAll(자식조직들(current));
            }
        }
        return found;
    }

    /** 조직도에 있는 직속 하위 조직들. 끊긴 참조는 조직이 아니다. */
    private Set<String> 자식조직들(String orgCode) {
        DirectoryGroup group = chart.snapshot().groups().get(orgCode);
        if (group == null) {
            return Set.of();
        }
        Set<String> children = new TreeSet<>();
        for (MemberRef member : group.members()) {
            if (member.type() == MemberType.GROUP && chart.snapshot().groups().containsKey(member.id())) {
                children.add(member.id());
            }
        }
        return children;
    }

    // ---------- 만들 때 한 번 ----------

    private static List<String> 끊긴참조들(DirectorySnapshot snapshot) {
        List<String> 끊긴것 = new ArrayList<>();
        for (DirectoryGroup group : 정렬한_조직(snapshot)) {
            for (MemberRef member : 정렬한_멤버(group)) {
                boolean 있다 = member.type() == MemberType.USER
                        ? snapshot.users().containsKey(member.id())
                        : snapshot.groups().containsKey(member.id());
                if (!있다) {
                    끊긴것.add("%s → %s:%s".formatted(group.id(), member.type(), member.id()));
                }
            }
        }
        return 끊긴것;
    }

    private static void 순환이_없어야_한다(DirectorySnapshot snapshot) {
        Map<String, Integer> 색 = new HashMap<>();   // 없음=미방문, 1=방문중, 2=끝남
        for (DirectoryGroup group : 정렬한_조직(snapshot)) {
            훑는다(group.id(), snapshot, 색, new ArrayList<>());
        }
    }

    private static void 훑는다(String node, DirectorySnapshot snapshot, Map<String, Integer> 색,
                            List<String> 경로) {
        Integer 현재 = 색.get(node);
        if (현재 != null && 현재 == 2) {
            return;
        }
        경로.add(node);
        if (현재 != null && 현재 == 1) {
            throw new IllegalArgumentException(
                    "조직도에 순환이 있습니다 — 기대값은 순환을 다루지 않습니다 (L16/S16 처럼 직접 Check "
                            + "하세요): " + String.join(" → ", 경로.subList(경로.indexOf(node), 경로.size())));
        }
        색.put(node, 1);
        DirectoryGroup group = snapshot.groups().get(node);
        for (MemberRef member : 정렬한_멤버(group)) {
            if (member.type() == MemberType.GROUP && snapshot.groups().containsKey(member.id())) {
                훑는다(member.id(), snapshot, 색, 경로);
            }
        }
        색.put(node, 2);
        경로.remove(경로.size() - 1);
    }

    private static Map<String, Set<String>> 부모들을_모은다(DirectorySnapshot snapshot) {
        Map<String, Set<String>> 부모들 = new HashMap<>();
        for (DirectoryGroup group : snapshot.groups().values()) {
            for (MemberRef member : group.members()) {
                if (member.type() == MemberType.GROUP && snapshot.groups().containsKey(member.id())) {
                    부모들.computeIfAbsent(member.id(), key -> new TreeSet<>()).add(group.id());
                }
            }
        }
        return 부모들;
    }

    private static Set<RelationTuple> 있어야할튜플을_모은다(DirectorySnapshot snapshot) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (DirectoryGroup group : 정렬한_조직(snapshot)) {
            for (MemberRef member : 정렬한_멤버(group)) {
                if (member.type() == MemberType.USER) {
                    DirectoryUser user = snapshot.users().get(member.id());
                    if (user != null && user.active()) {
                        tuples.add(RelationTuple.directMember(member.id(), group.id()));
                    }
                } else if (snapshot.groups().containsKey(member.id())) {
                    tuples.add(RelationTuple.child(member.id(), group.id()));
                }
            }
        }
        return tuples;
    }

    private static Set<RelationTuple> 후보를_모은다(OrgChart chart) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (DirectoryGroup group : 정렬한_조직(chart.snapshot())) {
            for (MemberRef member : 정렬한_멤버(group)) {
                tuples.add(튜플로(group.id(), member));
            }
        }
        chart.지워진멤버십().stream()
                .sorted(Comparator.comparing(Membership::조직).thenComparing(Membership::멤버, 멤버순))
                .forEach(m -> tuples.add(튜플로(m.조직(), m.멤버())));
        return tuples;
    }

    private static RelationTuple 튜플로(String groupId, MemberRef member) {
        return member.type() == MemberType.USER
                ? RelationTuple.directMember(member.id(), groupId)
                : RelationTuple.child(member.id(), groupId);
    }

    private static List<DirectoryGroup> 정렬한_조직(DirectorySnapshot snapshot) {
        return snapshot.groups().values().stream()
                .sorted(Comparator.comparing(DirectoryGroup::id)).toList();
    }

    private static List<MemberRef> 정렬한_멤버(DirectoryGroup group) {
        return group.members().stream().sorted(멤버순).toList();
    }
}

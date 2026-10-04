package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@link DirectorySnapshot} 을 OpenFGA 튜플 집합으로 변환한다.
 *
 * <p>이것이 LDAP 커넥터와 SCIM 커넥터가 공유하는 유일한 변환 규칙이다.
 * 조직명({@link DirectoryGroup#displayName()})은 조직 개편 때마다 바뀌므로 절대 튜플에 넣지 않는다.
 */
public final class TupleMapper {

    private TupleMapper() {
    }

    public static TupleMappingResult toTuples(DirectorySnapshot snapshot) {
        return toTuples(snapshot, Set.of());
    }

    /**
     * 보류 목록의 연결을 먼저 빼고 변환한다(설계 2026-10-03 §4.6). 남은 순환은 조직코드 사전순 DFS 로 버리고, 버린 연결을 결과의 {@code cutEdges} 에 담는다 —
     * SCIM 재적재는 빈 목록으로 불러 그것으로 보류 목록을 다시 쓰고, 아카이빙은 저장된 목록을 넘긴다. LDAP 은 늘 빈 목록이다.
     */
    public static TupleMappingResult toTuples(DirectorySnapshot snapshot, Set<GroupEdge> 보류) {
        List<String> warnings = new ArrayList<>();

        Map<String, Set<String>> childEdges = collectChildEdges(snapshot, warnings);
        childEdges.forEach((parent, children) -> children.removeIf(child -> 보류.contains(new GroupEdge(parent, child))));
        Set<GroupEdge> cut = new LinkedHashSet<>();
        Set<Edge> acyclic = removeCycles(childEdges, warnings, cut);

        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (Edge edge : acyclic) {
            tuples.add(RelationTuple.child(edge.child(), edge.parent()));
        }
        tuples.addAll(collectDirectMembers(snapshot, warnings));

        return new TupleMappingResult(tuples, warnings, cut);
    }

    /**
     * 순환을 버리지 않고 변환한다 — SCIM 순증 쓰기용(설계 2026-10-03 §4.3). 최소 그림 안의 DFS 는 조직코드 순서로 버릴 연결을 골라 한 홉 순환에서 두 연결을
     * 모두 버릴 수 있다(점검 S4). 순증 쓰기는 이것으로 목표를 만들고 순환 판단은 저장소의 튜플 그래프로 "먼저 저장된 연결이 이긴다" 규칙 하나로 한다.
     */
    public static TupleMappingResult toTuplesKeepingCycles(DirectorySnapshot snapshot) {
        List<String> warnings = new ArrayList<>();
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        collectChildEdges(snapshot, warnings).forEach((parent, children) ->
                children.forEach(child -> tuples.add(RelationTuple.child(child, parent))));
        tuples.addAll(collectDirectMembers(snapshot, warnings));
        return new TupleMappingResult(tuples, warnings, Set.of());
    }

    /**
     * 이 스냅샷의 멤버십에서 나올 수 있는 <b>모든</b> 튜플. `active` 필터도 순환 필터도
     * 적용하지 않는다 (설계 §5.1).
     *
     * <p><b>{@link #toTuples} 와 다른 질문에 답한다.</b> {@code toTuples} 는 "있어야 하는
     * 튜플" 이고 이쪽은 "혹시 있을지 모르는 튜플" 이다. 잘못 남은 튜플은 대개 비활성 직원의
     * 것이라(경합이 활성일 때 쓰고 지나갔으므로) 필터를 적용하면 정확히 그것을 놓친다.
     *
     * <p>이 집합은 <b>OpenFGA 에 물어볼 대상</b>일 뿐 쓰거나 지울 대상이 아니다.
     * 무엇을 쓰고 지울지는 이것과 {@code toTuples} 결과를 비교해 정한다.
     *
     * <p>id 는 {@code toTuples} 와 마찬가지로 정규화하지 않고 그대로 쓴다 — {@code toTuples}
     * 가 {@link IdNormalizer} 를 적용하지 않으므로, 여기서 적용하면 같은 논리적 튜플이
     * 서로 다른 문자열이 되어 diff 가 영원히 쓰고 지우기를 반복하게 된다.
     */
    public static Set<RelationTuple> candidateTuples(DirectorySnapshot snapshot) {
        Set<RelationTuple> candidates = new LinkedHashSet<>();
        for (DirectoryGroup group : snapshot.groups().values()) {
            for (MemberRef member : group.members()) {
                candidates.add(switch (member.type()) {
                    case USER -> RelationTuple.directMember(member.id(), group.id());
                    case GROUP -> RelationTuple.child(member.id(), group.id());
                });
            }
        }
        return candidates;
    }

    /** 조직코드 사전순으로 부모 → 자식 인접 리스트를 만든다. 순서를 고정해야 결과가 결정적이다. */
    private static Map<String, Set<String>> collectChildEdges(DirectorySnapshot snapshot, List<String> warnings) {
        Map<String, Set<String>> edges = new TreeMap<>();
        for (DirectoryGroup group : sortedGroups(snapshot)) {
            Set<String> children = new TreeSet<>();
            for (MemberRef member : sortedMembers(group)) {
                if (member.type() != MemberType.GROUP) {
                    continue;
                }
                if (!snapshot.groups().containsKey(member.id())) {
                    warnings.add("조직 '%s' 의 하위 조직 '%s' 가 스냅샷에 없어 건너뜁니다"
                            .formatted(group.id(), member.id()));
                    continue;
                }
                children.add(member.id());
            }
            edges.put(group.id(), children);
        }
        return edges;
    }

    private static Set<RelationTuple> collectDirectMembers(DirectorySnapshot snapshot, List<String> warnings) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (DirectoryGroup group : sortedGroups(snapshot)) {
            for (MemberRef member : sortedMembers(group)) {
                if (member.type() != MemberType.USER) {
                    continue;
                }
                DirectoryUser user = snapshot.users().get(member.id());
                if (user == null) {
                    warnings.add("조직 '%s' 의 직원 '%s' 가 스냅샷에 없어 건너뜁니다"
                            .formatted(group.id(), member.id()));
                    continue;
                }
                if (!user.active()) {
                    continue;
                }
                tuples.add(RelationTuple.directMember(user.id(), group.id()));
            }
        }
        return tuples;
    }

    /**
     * DFS 색칠법으로 순환을 찾아 back edge 만 버린다.
     * 시작점을 조직코드 사전순으로 고정했으므로 같은 입력이면 같은 간선이 버려진다. 버린 간선은 {@code cut} 에도 담는다.
     */
    private static Set<Edge> removeCycles(Map<String, Set<String>> edges, List<String> warnings, Set<GroupEdge> cut) {
        Set<Edge> kept = new LinkedHashSet<>();
        Map<String, Color> colors = new HashMap<>();
        edges.keySet().forEach(node -> colors.put(node, Color.WHITE));

        for (String start : edges.keySet()) {
            if (colors.get(start) == Color.WHITE) {
                visit(start, edges, colors, kept, warnings, cut);
            }
        }
        return kept;
    }

    private static void visit(String node,
                              Map<String, Set<String>> edges,
                              Map<String, Color> colors,
                              Set<Edge> kept,
                              List<String> warnings,
                              Set<GroupEdge> cut) {
        colors.put(node, Color.GRAY);
        for (String child : edges.getOrDefault(node, Set.of())) {
            Color childColor = colors.getOrDefault(child, Color.WHITE);
            if (childColor == Color.GRAY) {
                warnings.add("조직 '%s' → '%s' 간선이 순환을 만들어 제외합니다".formatted(node, child));
                cut.add(new GroupEdge(node, child));
                continue;
            }
            kept.add(new Edge(child, node));
            if (childColor == Color.WHITE) {
                visit(child, edges, colors, kept, warnings, cut);
            }
        }
        colors.put(node, Color.BLACK);
    }

    private static List<DirectoryGroup> sortedGroups(DirectorySnapshot snapshot) {
        return snapshot.groups().values().stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .toList();
    }

    private static List<MemberRef> sortedMembers(DirectoryGroup group) {
        return group.members().stream()
                .sorted((a, b) -> {
                    int byType = a.type().compareTo(b.type());
                    return byType != 0 ? byType : a.id().compareTo(b.id());
                })
                .toList();
    }

    /** child 가 parent 의 하위 조직이다. 튜플 방향과 동일하다. */
    private record Edge(String child, String parent) {
    }

    private enum Color {
        WHITE, GRAY, BLACK
    }
}

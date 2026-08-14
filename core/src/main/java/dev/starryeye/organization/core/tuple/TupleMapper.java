package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
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
        List<String> warnings = new ArrayList<>();

        Map<String, Set<String>> childEdges = collectChildEdges(snapshot, warnings);
        Set<Edge> acyclic = removeCycles(childEdges, warnings);

        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (Edge edge : acyclic) {
            tuples.add(RelationTuple.child(edge.child(), edge.parent()));
        }
        tuples.addAll(collectDirectMembers(snapshot, warnings));

        return new TupleMappingResult(tuples, warnings);
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
     * 시작점을 조직코드 사전순으로 고정했으므로 같은 입력이면 같은 간선이 버려진다.
     */
    private static Set<Edge> removeCycles(Map<String, Set<String>> edges, List<String> warnings) {
        Set<Edge> kept = new LinkedHashSet<>();
        Map<String, Color> colors = new HashMap<>();
        edges.keySet().forEach(node -> colors.put(node, Color.WHITE));

        for (String start : edges.keySet()) {
            if (colors.get(start) == Color.WHITE) {
                visit(start, edges, colors, kept, warnings);
            }
        }
        return kept;
    }

    private static void visit(String node,
                              Map<String, Set<String>> edges,
                              Map<String, Color> colors,
                              Set<Edge> kept,
                              List<String> warnings) {
        colors.put(node, Color.GRAY);
        for (String child : edges.getOrDefault(node, Set.of())) {
            Color childColor = colors.getOrDefault(child, Color.WHITE);
            if (childColor == Color.GRAY) {
                warnings.add("조직 '%s' → '%s' 간선이 순환을 만들어 제외합니다".formatted(node, child));
                continue;
            }
            kept.add(new Edge(child, node));
            if (childColor == Color.WHITE) {
                visit(child, edges, colors, kept, warnings);
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

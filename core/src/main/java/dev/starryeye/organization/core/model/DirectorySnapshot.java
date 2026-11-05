package dev.starryeye.organization.core.model;

import java.util.Map;
import java.util.Set;

/**
 * @param assignedEdges 운영자가 조직마다 하나씩 지정한 하위 조직 연결(설계 2026-11-04 §5.2). 멤버 행의 하위 조직 연결과 합쳐 튜플이 된다.
 *                      LDAP 그림은 늘 빈 집합이다
 */
public record DirectorySnapshot(
        Map<String, DirectoryUser> users,
        Map<String, DirectoryGroup> groups,
        Set<GroupEdge> assignedEdges
) {

    public DirectorySnapshot {
        users = users == null ? Map.of() : Map.copyOf(users);
        groups = groups == null ? Map.of() : Map.copyOf(groups);
        assignedEdges = assignedEdges == null ? Set.of() : Set.copyOf(assignedEdges);
    }

    /** 지정 연결이 없는 그림 — LDAP 과 지정 연결을 모르는 호출처가 쓴다. */
    public DirectorySnapshot(Map<String, DirectoryUser> users, Map<String, DirectoryGroup> groups) {
        this(users, groups, Set.of());
    }

    public static DirectorySnapshot empty() {
        return new DirectorySnapshot(Map.of(), Map.of());
    }

    /** 나머지 칸은 그대로이고 지정 연결만 바뀐 새 그림. */
    public DirectorySnapshot withAssignedEdges(Set<GroupEdge> edges) {
        return new DirectorySnapshot(users, groups, edges);
    }
}

package dev.starryeye.organization.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * 하위 조직 연결 — {@code child} 가 {@code parent} 의 하위 조직이다. 튜플 {@code (group:child, child, group:parent)} 와 같다.
 * 순환이라 쓰지 않은 연결의 보류 목록(설계 2026-10-03 §4.1)이 이 값으로 적힌다.
 */
public record GroupEdge(String parent, String child) {

    public GroupEdge {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
    }

    public RelationTuple tuple() {
        return RelationTuple.child(child, parent);
    }

    /** 하위 조직 연결 튜플이면 그 연결, 아니면 빈 값. */
    public static Optional<GroupEdge> of(RelationTuple tuple) {
        if (!RelationTuple.CHILD.equals(tuple.relation())) {
            return Optional.empty();
        }
        return Optional.of(new GroupEdge(idOf(tuple.object()), idOf(tuple.user())));
    }

    private static String idOf(String typedId) {
        int separator = typedId.indexOf(':');
        return separator < 0 ? typedId : typedId.substring(separator + 1);
    }
}

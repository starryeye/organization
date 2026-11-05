package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DirectorySnapshotTest {

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", true);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, "cn=" + code, code + " 조직", Set.of(members));
    }

    @Test
    @DisplayName("두 인자 생성자는 지정 연결이 비었다 — 기존 호출처는 그대로 둔다")
    void 두_인자_생성자는_지정_연결이_비었다() {
        // given
        var users = Map.of("kim", 직원("kim"));
        var groups = Map.of("P", 조직("P", MemberRef.user("kim")));

        // when
        var snapshot = new DirectorySnapshot(users, groups);

        // then
        assertThat(snapshot.assignedEdges()).isEmpty();
        assertThat(snapshot.users()).isEqualTo(users);
        assertThat(snapshot.groups()).isEqualTo(groups);
        assertThat(DirectorySnapshot.empty().assignedEdges()).isEmpty();
    }

    @Test
    @DisplayName("withAssignedEdges 는 다른 칸을 그대로 두고 지정 연결만 바꾼다")
    void withAssignedEdges_는_지정_연결만_바꾼다() {
        // given
        var users = Map.of("kim", 직원("kim"));
        var groups = Map.of("P", 조직("P", MemberRef.user("kim")), "C", 조직("C"));
        var 원본 = new DirectorySnapshot(users, groups, Set.of(new GroupEdge("P", "C")));

        // when
        var 바뀐 = 원본.withAssignedEdges(Set.of(new GroupEdge("C", "P")));

        // then
        assertThat(바뀐.users()).isEqualTo(users);
        assertThat(바뀐.groups()).isEqualTo(groups);
        assertThat(바뀐.assignedEdges()).containsExactly(new GroupEdge("C", "P"));
        assertThat(원본.assignedEdges()).as("원본은 그대로다").containsExactly(new GroupEdge("P", "C"));
    }

    @Test
    @DisplayName("null 은 빈 집합이다")
    void null_은_빈_집합이다() {
        // given / when
        var snapshot = new DirectorySnapshot(Map.of(), Map.of(), null);

        // then
        assertThat(snapshot.assignedEdges()).isEmpty();
        assertThat(snapshot.withAssignedEdges(null).assignedEdges()).isEmpty();
    }
}

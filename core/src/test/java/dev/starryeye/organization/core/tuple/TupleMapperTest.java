package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TupleMapperTest {

    private static DirectoryUser 활성직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", true);
    }

    private static DirectoryUser 비활성직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", false);
    }

    private static DirectoryGroup 조직(String code, String name, MemberRef... members) {
        return new DirectoryGroup(code, "cn=" + code, name, Set.of(members));
    }

    private static DirectorySnapshot 스냅샷(Set<DirectoryUser> users, Set<DirectoryGroup> groups) {
        return new DirectorySnapshot(
                users.stream().collect(Collectors.toMap(DirectoryUser::id, Function.identity())),
                groups.stream().collect(Collectors.toMap(DirectoryGroup::id, Function.identity())));
    }

    @Test
    @DisplayName("조직에 직접 속한 직원은 direct_member 튜플이 된다")
    void 직원_멤버는_direct_member_튜플이_된다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("kim"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .containsExactly(new RelationTuple("user:kim", "direct_member", "group:DEV002"));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("조직에 속한 하위 조직은 child 튜플이 되며 방향은 하위에서 상위로 향한다")
    void 조직_멤버는_child_튜플이_된다() {
        // given
        var snapshot = 스냅샷(
                Set.of(),
                Set.of(조직("DEV001", "개발본부", MemberRef.group("DEV002")),
                       조직("DEV002", "백엔드팀")));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .containsExactly(new RelationTuple("group:DEV002", "child", "group:DEV001"));
    }

    @Test
    @DisplayName("비활성 직원은 튜플이 생성되지 않아 권한이 남지 않는다")
    void 비활성_직원은_튜플이_생성되지_않는다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim"), 비활성직원("lee")),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .containsExactly(new RelationTuple("user:kim", "direct_member", "group:DEV002"));
    }

    @Test
    @DisplayName("스냅샷에 존재하지 않는 멤버는 건너뛰고 경고로 남긴다")
    void 존재하지_않는_멤버는_경고로_남긴다() {
        // given
        var snapshot = 스냅샷(
                Set.of(),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("ghost"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("ghost").contains("DEV002");
    }

    @Test
    @DisplayName("조직명은 조직 개편에 따라 바뀌므로 튜플에 포함되지 않는다")
    void 조직명은_튜플에_포함되지_않는다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("kim"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .allSatisfy(tuple -> assertThat(tuple.object()).doesNotContain("백엔드팀"));
    }

    @Test
    @DisplayName("조직 계층에 순환이 있으면 순환을 만드는 간선만 제외하고 나머지는 유지한다")
    void 순환_참조는_간선을_제외하고_동기화를_완주한다() {
        // given — A -> B -> C -> A 로 순환하고, B 아래에 직원이 하나 있다
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("A", "가", MemberRef.group("B")),
                       조직("B", "나", MemberRef.group("C"), MemberRef.user("kim")),
                       조직("C", "다", MemberRef.group("A"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then — child 간선 3개 중 순환을 닫는 1개만 빠지고 2개가 남는다
        var childTuples = result.tuples().stream()
                .filter(t -> t.relation().equals("child"))
                .collect(Collectors.toSet());
        assertThat(childTuples).hasSize(2);
        assertThat(result.tuples())
                .contains(new RelationTuple("user:kim", "direct_member", "group:B"));
        assertThat(result.warnings())
                .anySatisfy(w -> assertThat(w).contains("순환"));
    }

    @Test
    @DisplayName("동일한 스냅샷을 여러 번 변환해도 항상 같은 결과가 나온다")
    void 변환_결과는_결정적이다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("A", "가", MemberRef.group("B")),
                       조직("B", "나", MemberRef.group("C"), MemberRef.user("kim")),
                       조직("C", "다", MemberRef.group("A"))));

        // when
        var first = TupleMapper.toTuples(snapshot);
        var again = Stream.generate(() -> TupleMapper.toTuples(snapshot))
                .limit(5)
                .map(TupleMappingResult::tuples)
                .collect(Collectors.toSet());

        // then
        assertThat(again).containsExactly(first.tuples());
    }

    @Test
    @DisplayName("빈 스냅샷은 빈 튜플 집합을 만든다")
    void 빈_스냅샷은_빈_결과를_만든다() {
        // given
        var snapshot = DirectorySnapshot.empty();

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("중첩 조직과 직원이 섞인 스냅샷을 한 번에 변환한다")

    void 복합_스냅샷을_변환한다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim"), 활성직원("lee"), 활성직원("park")),
                Set.of(조직("DEV001", "개발본부", MemberRef.group("DEV002"), MemberRef.user("park")),
                       조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples()).containsExactlyInAnyOrder(
                new RelationTuple("group:DEV002", "child", "group:DEV001"),
                new RelationTuple("user:park", "direct_member", "group:DEV001"),
                new RelationTuple("user:kim", "direct_member", "group:DEV002"),
                new RelationTuple("user:lee", "direct_member", "group:DEV002"));
        assertThat(result.warnings()).isEmpty();
    }
}

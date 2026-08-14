package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** 멤버 목록의 삽입 순서를 보존하는 조직. {@link DirectoryGroup} 은 {@code Set.copyOf} 로
     *  멤버를 복사하므로, 삽입 순서가 살아남는지는 실제로 사용하는 키에 달려 있다. */
    private static DirectoryGroup 조직_순서(String code, String name, List<MemberRef> members) {
        return new DirectoryGroup(code, "cn=" + code, name, new LinkedHashSet<>(members));
    }

    /** 유저/조직 맵의 삽입 순서를 {@link LinkedHashMap} 으로 보존해 만든 스냅샷.
     *  {@link DirectorySnapshot} 은 {@code Map.copyOf} 로 맵을 복사하므로, 삽입 순서가
     *  살아남는지는 실제로 사용하는 키에 달려 있다. */
    private static DirectorySnapshot 순서있는_스냅샷(List<DirectoryUser> users, List<DirectoryGroup> groups) {
        Map<String, DirectoryUser> userMap = new LinkedHashMap<>();
        for (DirectoryUser user : users) {
            userMap.put(user.id(), user);
        }
        Map<String, DirectoryGroup> groupMap = new LinkedHashMap<>();
        for (DirectoryGroup group : groups) {
            groupMap.put(group.id(), group);
        }
        return new DirectorySnapshot(userMap, groupMap);
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
    @DisplayName("같은 조직 데이터라도 읽은 순서가 다르면 다른 스냅샷 객체가 되지만 결과 튜플은 같다")
    void 조직_데이터를_읽은_순서와_무관하게_같은_튜플이_나온다() {
        // given — 두 스냅샷은 논리적으로 같은 순환 구조(Aa -> BB -> Z -> Aa)를 담지만
        // 그룹·유저·멤버의 삽입 순서가 정반대다.
        //
        // "Aa" 와 "BB" 는 String.hashCode() 가 우연히 같다(2112). A/B/C 같은 평범한
        // 조직코드로는 DirectorySnapshot/DirectoryGroup 의 압축 생성자가 수행하는
        // Map.copyOf/Set.copyOf 가 삽입 순서와 무관하게 항상 같은 반복 순서를 만들어내
        // 이 회귀 테스트가 아무것도 잡아내지 못한다(직접 확인함). 해시가 충돌하는 키를
        // 하나 넣어야만 삽입 순서에 따라 실제로 다른 반복 순서가 만들어진다.
        var forward = 순서있는_스냅샷(
                List.of(활성직원("kim"), 활성직원("lee")),
                List.of(
                        조직_순서("Aa", "가", List.of(MemberRef.group("BB"), MemberRef.user("lee"))),
                        조직_순서("BB", "나", List.of(MemberRef.group("Z"), MemberRef.user("kim"))),
                        조직_순서("Z", "다", List.of(MemberRef.group("Aa")))));

        var reversed = 순서있는_스냅샷(
                List.of(활성직원("lee"), 활성직원("kim")),
                List.of(
                        조직_순서("Z", "다", List.of(MemberRef.group("Aa"))),
                        조직_순서("BB", "나", List.of(MemberRef.user("kim"), MemberRef.group("Z"))),
                        조직_순서("Aa", "가", List.of(MemberRef.user("lee"), MemberRef.group("BB")))));

        // when
        var forwardResult = TupleMapper.toTuples(forward);
        var reversedResult = TupleMapper.toTuples(reversed);

        // then — 순환을 닫는 간선이 항상 같은 곳에서 끊긴다
        assertThat(reversedResult.tuples()).isEqualTo(forwardResult.tuples());
        assertThat(forwardResult.tuples().stream().filter(t -> t.relation().equals("child")).count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("조직이 자기 자신을 하위 조직으로 포함해도 그 간선만 제외되고 나머지는 유지된다")
    void 자기_자신을_멤버로_포함한_조직은_해당_간선만_제외된다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.group("DEV002"), MemberRef.user("kim"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples()).noneMatch(t -> t.relation().equals("child"));
        assertThat(result.tuples())
                .contains(new RelationTuple("user:kim", "direct_member", "group:DEV002"));
        assertThat(result.warnings())
                .anySatisfy(w -> assertThat(w).contains("순환"));
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

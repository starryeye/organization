package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후보 집합 (설계 §5.1).
 *
 * <p><b>왜 별도 계산이 필요한가.</b> {@link TupleMapper#toTuples} 는 "있어야 하는 튜플" 을
 * 준다 — 비활성 직원과 순환 간선을 빼고 준다. 그런데 우리가 OpenFGA 에 물어봐야 하는 것은
 * "혹시 있을지 모르는 튜플" 이다. 비활성이라 빠진 바로 그 튜플이 잘못 남아 있는 경우를
 * 잡으려는 것이므로, 필터를 적용하기 <b>전</b>의 멤버십에서 뽑아야 한다.
 */
class CandidateTuplesTest {

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, "cn=" + code, code + " 조직", Set.of(members));
    }

    private static DirectorySnapshot 스냅샷(Set<DirectoryUser> users, Set<DirectoryGroup> groups) {
        return new DirectorySnapshot(
                users.stream().collect(Collectors.toMap(DirectoryUser::id, Function.identity())),
                groups.stream().collect(Collectors.toMap(DirectoryGroup::id, Function.identity())));
    }

    @Test
    @DisplayName("비활성 직원의 튜플도 후보에 들어간다 — 잘못 남은 그것을 잡아야 하므로")
    void 비활성_직원도_후보다() {
        // given — kim 은 DEV001 멤버지만 비활성이다. §1 경합이 남기는 바로 그 모양이다.
        var snapshot = 스냅샷(
                Set.of(직원("kim", false), 직원("park", true)),
                Set.of(조직("DEV001", MemberRef.user("kim"), MemberRef.user("park"))));

        // when
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(snapshot);

        // then — toTuples 라면 kim 이 빠지지만, 후보에는 있어야 한다
        assertThat(후보).contains(RelationTuple.directMember("kim", "DEV001"));
        assertThat(후보).contains(RelationTuple.directMember("park", "DEV001"));
        assertThat(TupleMapper.toTuples(snapshot).tuples())
                .as("대조: toTuples 는 비활성을 뺀다")
                .doesNotContain(RelationTuple.directMember("kim", "DEV001"));
    }

    @Test
    @DisplayName("순환을 만드는 간선도 후보에 들어간다 — 잘못 쓰였을 수 있으므로")
    void 순환_간선도_후보다() {
        // given — A -> B -> A 순환
        var snapshot = 스냅샷(
                Set.of(),
                Set.of(조직("A", MemberRef.group("B")), 조직("B", MemberRef.group("A"))));

        // when
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(snapshot);

        // then — 두 간선 모두 후보다. toTuples 는 하나를 버린다.
        assertThat(후보).contains(
                RelationTuple.child("B", "A"),
                RelationTuple.child("A", "B"));
    }

    @Test
    @DisplayName("스냅샷에 없는 멤버가 참조돼도 후보에 들어간다")
    void 스냅샷에_없는_멤버도_후보다() {
        // given — DEV001 이 아직 스냅샷에 없는 choi 를 멤버로 적고 있다
        var snapshot = 스냅샷(Set.of(), Set.of(조직("DEV001", MemberRef.user("choi"))));

        // when
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(snapshot);

        // then — 그 튜플이 잘못 쓰여 있을 수 있으므로 확인 대상이다
        assertThat(후보).contains(RelationTuple.directMember("choi", "DEV001"));
    }

    @Test
    @DisplayName("멤버가 없는 조직은 후보를 만들지 않는다")
    void 멤버가_없으면_후보도_없다() {
        // given
        var snapshot = 스냅샷(Set.of(), Set.of(조직("DEV001")));

        // when, then
        assertThat(TupleMapper.candidateTuples(snapshot)).isEmpty();
    }
}

package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class GroupChangeTest {

    /** 모호하지 않으면 부르지 않아야 한다 — 불리면 테스트가 깨진다. */
    private static final Predicate<String> 부르면_안된다 = id -> {
        throw new AssertionError("모호하지 않은데 종류를 물었다: " + id);
    };

    private static DirectoryGroup 조직(MemberRef... members) {
        return new DirectoryGroup("DEV", "cn=DEV", "개발본부", Set.of(members));
    }

    @Test
    @DisplayName("증분은 연산을 순서대로 적용한다 — 넣고 빼고")
    void 연산을_순서대로_적용한다() {
        // given
        var change = GroupChange.delta()
                .adding(Set.of(MemberRef.user("lee")))
                .removingId("kim");

        // when
        var after = change.applyTo(조직(MemberRef.user("kim"), MemberRef.user("park")), 부르면_안된다);

        // then
        assertThat(after.members()).containsExactlyInAnyOrder(MemberRef.user("park"), MemberRef.user("lee"));
        assertThat(after.displayName()).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("넣은 뒤 같은 id 를 빼면 변화가 없다")
    void 넣고_빼면_그대로다() {
        // given
        var change = GroupChange.delta().adding(Set.of(MemberRef.user("lee"))).removingId("lee");

        // when
        var after = change.applyTo(조직(MemberRef.user("kim")), 부르면_안된다);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("id 빼기가 직원·하위 조직 둘 다에 맞으면 조직이면 에 따라 한쪽만 뺀다")
    void 모호한_빼기는_한쪽만_뺀다() {
        // given
        var before = 조직(MemberRef.user("X"), MemberRef.group("X"));
        var change = GroupChange.delta().removingId("X");

        // when
        var 조직이다 = change.applyTo(before, id -> true);
        var 직원이다 = change.applyTo(before, id -> false);

        // then
        assertThat(조직이다.members()).containsExactly(MemberRef.user("X"));
        assertThat(직원이다.members()).containsExactly(MemberRef.group("X"));
    }

    @Test
    @DisplayName("전체 교체는 앞의 연산을 버리고 목록에서 뒤의 연산을 적용한다")
    void 교체는_앞_연산을_버린다() {
        // given
        var change = GroupChange.delta()
                .adding(Set.of(MemberRef.user("ghost")))
                .replacing(Set.of(MemberRef.user("kim"), MemberRef.user("park")))
                .removingId("kim");

        // when
        var after = change.applyTo(조직(MemberRef.user("lee")), 부르면_안된다);

        // then
        assertThat(change.replacesMembers()).isTrue();
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("연산이 가리키는 멤버 — id 빼기는 종류를 모르므로 직원·하위 조직 둘 다다")
    void 가리키는_멤버() {
        // given
        var change = GroupChange.delta().adding(Set.of(MemberRef.group("TEAM"))).removingId("kim");

        // when, then
        assertThat(change.mentioned()).containsExactlyInAnyOrder(
                MemberRef.group("TEAM"), MemberRef.user("kim"), MemberRef.group("kim"));
    }

    @Test
    @DisplayName("둘 다 멤버일 수 있는 id 빼기만 모호하다")
    void 모호한_id() {
        // given
        var change = GroupChange.delta()
                .adding(Set.of(MemberRef.group("X")))
                .removingId("X")
                .removingId("kim");

        // when, then — X 는 직원으로 이미 있고 하위 조직으로 들어온다. kim 은 직원만 있다
        assertThat(change.ambiguousIds(Set.of(MemberRef.user("X"), MemberRef.user("kim"))))
                .containsExactly("X");
    }

    @Test
    @DisplayName("이름만 바꾸면 externalId 는 그대로, PUT 은 둘 다 바꾼다")
    void 헤더를_바꾼다() {
        // given
        var header = new GroupHeader("DEV", "cn=DEV", "개발본부");

        // when
        var 이름만 = GroupChange.delta().renamed("플랫폼본부").applyTo(header);
        var put = GroupChange.replacement("cn=DEV-2", "개발본부2", Set.of()).applyTo(header);
        var 그대로 = GroupChange.delta().applyTo(header);

        // then
        assertThat(이름만).isEqualTo(new GroupHeader("DEV", "cn=DEV", "플랫폼본부"));
        assertThat(put).isEqualTo(new GroupHeader("DEV", "cn=DEV-2", "개발본부2"));
        assertThat(그대로).isEqualTo(header);
    }

    @Test
    @DisplayName("PUT 은 목록 전체로 교체한다")
    void PUT은_전체_교체다() {
        // given
        var change = GroupChange.replacement("cn=DEV", "개발본부", Set.of(MemberRef.user("park")));

        // when
        var after = change.applyTo(조직(MemberRef.user("kim")), 부르면_안된다);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }
}

package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static dev.starryeye.organization.core.model.RelationTuple.child;
import static dev.starryeye.organization.core.model.RelationTuple.directMember;
import static dev.starryeye.organization.core.model.RelationTuple.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조직도 → 기대값 규칙을 작은 조직도로 하나씩 못박는다 (스펙 §4).
 *
 * <pre>
 * CORP ─┬─ DEV ─┬─ TEAM   (a: 활성)
 *       │       └─ TEAM2  (d: 활성)
 *       │  (DEV 직속 b: 비활성)
 *       └─ MGT            (c: 활성)
 * </pre>
 */
class ChartExpectationTest {

    private final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    private final Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

    ChartExpectationTest() {
        직원("a", true);
        직원("b", false);
        직원("c", true);
        직원("d", true);
        조직("CORP", MemberRef.group("DEV"), MemberRef.group("MGT"));
        조직("DEV", MemberRef.group("TEAM"), MemberRef.group("TEAM2"), MemberRef.user("b"));
        조직("TEAM", MemberRef.user("a"));
        조직("TEAM2", MemberRef.user("d"));
        조직("MGT", MemberRef.user("c"));
    }

    @Test
    @DisplayName("활성 직원의 소속마다 direct_member, 조직 간선마다 child 를 요구한다")
    void 있어야할_튜플() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — b 는 비활성이라 없다
        assertThat(기대.있어야할튜플()).containsExactlyInAnyOrder(
                child("DEV", "CORP"), child("MGT", "CORP"),
                child("TEAM", "DEV"), child("TEAM2", "DEV"),
                directMember("a", "TEAM"), directMember("d", "TEAM2"), directMember("c", "MGT"));
    }

    @Test
    @DisplayName("비활성 직원의 멤버십은 후보로 묻는다 — 퇴사자 권한 생존을 잡는 자리")
    void 비활성은_후보에_있다() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.물어볼후보()).contains(directMember("b", "DEV"));
        assertThat(기대.있어야할튜플()).doesNotContain(directMember("b", "DEV"));
        assertThat(기대.물어볼후보()).containsAll(기대.있어야할튜플());
    }

    @Test
    @DisplayName("지워진 멤버십은 후보로 묻고, 다시 넣은 것은 있어야 할 쪽이 이긴다")
    void 지워진_멤버십은_후보다() {
        // given — a 가 TEAM 에서 빠진 적이 있고, c 는 MGT 에서 빠졌다가 다시 들어왔다
        조직("TEAM");
        var 조직도 = new OrgChart(new DirectorySnapshot(users, groups), null, Set.of(
                new Membership("TEAM", MemberRef.user("a")),
                new Membership("MGT", MemberRef.user("c"))));

        // when
        var 기대 = ChartExpectation.of(조직도);

        // then
        assertThat(기대.물어볼후보()).contains(directMember("a", "TEAM"));
        assertThat(기대.있어야할튜플()).doesNotContain(directMember("a", "TEAM"));
        assertThat(기대.있어야할튜플()).contains(directMember("c", "MGT"));
    }

    @Test
    @DisplayName("끊긴 참조는 기본으로 거부한다 — 대개 시나리오 버그다")
    void 끊긴_참조는_거부한다() {
        // given — 없는 직원과 없는 조직을 가리킨다
        조직("MGT", MemberRef.user("c"), MemberRef.user("ghost"), MemberRef.group("NOWHERE"));

        // when, then
        assertThatThrownBy(() -> ChartExpectation.of(조직도()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("NOWHERE")
                .hasMessageContaining("끊긴참조를_허용하며");
    }

    @Test
    @DisplayName("끊긴 참조를 허용하면 튜플은 기대하지 않되 없어야 함을 묻는다")
    void 허용하면_후보로_묻는다() {
        // given
        조직("MGT", MemberRef.user("c"), MemberRef.user("ghost"), MemberRef.group("NOWHERE"));

        // when
        var 기대 = ChartExpectation.끊긴참조를_허용하며(조직도());

        // then — 운영이 없는 대상에게 튜플을 지어내면 ③이 잡는다
        assertThat(기대.물어볼후보()).contains(directMember("ghost", "MGT"), child("NOWHERE", "MGT"));
        assertThat(기대.있어야할튜플()).doesNotContain(directMember("ghost", "MGT"), child("NOWHERE", "MGT"));
    }

    @Test
    @DisplayName("지워진 멤버십은 끊긴 참조 검사에서 빠진다 — 지운 직원을 가리키는 것이 당연하다")
    void 기억은_끊긴_참조가_아니다() {
        // given — ghost 는 지워졌고, 그 멤버십만 기억에 있다
        var 조직도 = new OrgChart(new DirectorySnapshot(users, groups), null,
                Set.of(new Membership("MGT", MemberRef.user("ghost"))));

        // when
        var 기대 = ChartExpectation.of(조직도);

        // then
        assertThat(기대.물어볼후보()).contains(directMember("ghost", "MGT"));
    }

    @Test
    @DisplayName("순환은 어느 팩토리로도 거부한다 — 어느 간선을 버릴지는 구현 세부다")
    void 순환은_거부한다() {
        // given — TEAM 이 CORP 를 하위로 갖는다: CORP → DEV → TEAM → CORP
        조직("TEAM", MemberRef.user("a"), MemberRef.group("CORP"));

        // when, then
        assertThatThrownBy(() -> ChartExpectation.of(조직도()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("순환");
        assertThatThrownBy(() -> ChartExpectation.끊긴참조를_허용하며(조직도()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("순환");
    }

    @Test
    @DisplayName("롤업 양성은 직속 조직과 모든 조상이다")
    void 롤업_양성() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.롤업양성("a")).containsExactlyInAnyOrder(
                member("a", "TEAM"), member("a", "DEV"), member("a", "CORP"));
    }

    @Test
    @DisplayName("롤업 음성은 자손과 형제 가지다 — 기대소속은 빠진다")
    void 롤업_음성() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — a 의 형제 가지 TEAM2. 자손은 없다
        assertThat(기대.롤업음성("a")).containsExactly(member("a", "TEAM2"));
        // c 는 MGT 직속 — 형제 가지 DEV
        assertThat(기대.롤업음성("c")).containsExactly(member("c", "DEV"));
    }

    @Test
    @DisplayName("비활성 직원은 양성이 없고 기대소속까지 음성이다")
    void 비활성_롤업() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — b 는 DEV 직속: 자손 TEAM·TEAM2, 형제 MGT, 그리고 자기 소속 DEV·CORP 까지
        assertThat(기대.롤업양성("b")).isEmpty();
        assertThat(기대.롤업음성("b")).containsExactlyInAnyOrder(
                member("b", "TEAM"), member("b", "TEAM2"), member("b", "MGT"),
                member("b", "DEV"), member("b", "CORP"));
    }

    @Test
    @DisplayName("부모가 둘인 조직의 조상은 두 갈래 모두다")
    void 다중_부모() {
        // given — X 가 TEAM 과 MGT 양쪽의 하위 조직
        직원("x", true);
        조직("X", MemberRef.user("x"));
        조직("TEAM", MemberRef.user("a"), MemberRef.group("X"));
        조직("MGT", MemberRef.user("c"), MemberRef.group("X"));

        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.롤업양성("x")).containsExactlyInAnyOrder(
                member("x", "X"), member("x", "TEAM"), member("x", "DEV"),
                member("x", "MGT"), member("x", "CORP"));
    }

    @Test
    @DisplayName("없는 직원의 롤업은 묻지 않는다")
    void 없는_직원의_롤업() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.롤업양성("nobody")).isEmpty();
        assertThat(기대.롤업음성("nobody")).isEmpty();
    }

    // ---------- 거들기 ----------

    private void 직원(String id, boolean active) {
        users.put(id, new DirectoryUser(id, null, id, id, null, active));
    }

    private void 조직(String id, MemberRef... members) {
        groups.put(id, new DirectoryGroup(id, null, id, Set.of(members)));
    }

    private OrgChart 조직도() {
        return new OrgChart(new DirectorySnapshot(users, groups), null);
    }
}

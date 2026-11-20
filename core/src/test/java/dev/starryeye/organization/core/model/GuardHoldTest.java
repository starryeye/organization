package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GuardHoldTest {

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code) {
        return new DirectoryGroup(code, "cn=" + code, code + " 조직", Set.of());
    }

    private static DirectorySnapshot 그림(List<DirectoryUser> users, List<DirectoryGroup> groups) {
        Map<String, DirectoryUser> u = new HashMap<>();
        users.forEach(x -> u.put(x.id(), x));
        Map<String, DirectoryGroup> g = new HashMap<>();
        groups.forEach(x -> g.put(x.id(), x));
        return new DirectorySnapshot(u, g, Set.of());
    }

    @Test
    @DisplayName("줄은 관계로 센다")
    void 줄은_관계로_센다() {
        // given
        var deletes = Set.of(RelationTuple.directMember("U", "G"), RelationTuple.child("C", "P"),
                new RelationTuple("user:U", "viewer", "group:G"));

        // when
        var hold = GuardHold.of(deletes, DirectorySnapshot.empty());

        // then
        assertThat(hold.directMemberLines()).isEqualTo(1);
        assertThat(hold.childLines()).isEqualTo(1);
        assertThat(hold.otherLines()).isEqualTo(1);
        assertThat(hold.totalLines()).isEqualTo(3);
    }

    @Test
    @DisplayName("직원은 그림대로 가른다")
    void 직원은_그림대로_가른다() {
        // given
        var directory = 그림(List.of(직원("U2", false), 직원("U3", true)), List.of(조직("G")));
        var deletes = Set.of(RelationTuple.directMember("U1", "G"), RelationTuple.directMember("U2", "G"),
                RelationTuple.directMember("U3", "G"));

        // when
        var hold = GuardHold.of(deletes, directory);

        // then
        assertThat(hold.usersGone().count()).isEqualTo(1);
        assertThat(hold.usersGone().ids()).containsExactly("U1");
        assertThat(hold.usersInactive().ids()).containsExactly("U2");
        assertThat(hold.usersLosingMembership().ids()).containsExactly("U3");
    }

    @Test
    @DisplayName("같은 직원의 줄 셋은 한 명이다")
    void 같은_직원의_줄_셋은_한_명이다() {
        // given
        var deletes = Set.of(RelationTuple.directMember("U1", "G1"), RelationTuple.directMember("U1", "G2"),
                RelationTuple.directMember("U1", "G3"));

        // when
        var hold = GuardHold.of(deletes, DirectorySnapshot.empty());

        // then
        assertThat(hold.usersGone().count()).isEqualTo(1);
        assertThat(hold.directMemberLines()).isEqualTo(3);
    }

    @Test
    @DisplayName("하위 조직 연결은 C 로 가르고 P 가 없으면 사라진 조직이다")
    void 하위_조직_연결은_C_로_가르고_P_가_없으면_사라진_조직이다() {
        // given
        var directory = 그림(List.of(), List.of(조직("C1")));
        var deletes = Set.of(RelationTuple.child("C1", "P1"), RelationTuple.child("C2", "P2"));

        // when
        var hold = GuardHold.of(deletes, directory);

        // then
        assertThat(hold.groupsLosingParent().ids()).containsExactly("C1");
        assertThat(hold.groupsGone().ids()).containsExactly("C2", "P1", "P2");
    }

    @Test
    @DisplayName("소속 줄의 조직이 없으면 사라진 조직이다")
    void 소속_줄의_조직이_없으면_사라진_조직이다() {
        // given
        var deletes = Set.of(RelationTuple.directMember("U", "G9"), RelationTuple.directMember("V", "G9"));

        // when
        var hold = GuardHold.of(deletes, DirectorySnapshot.empty());

        // then
        assertThat(hold.groupsGone().count()).isEqualTo(1);
        assertThat(hold.groupsGone().ids()).containsExactly("G9");
    }

    @Test
    @DisplayName("예는 사전순 앞 10개다")
    void 예는_사전순_앞_10개다() {
        // given
        Set<RelationTuple> deletes = new LinkedHashSet<>();
        for (int i = 15; i >= 1; i--) {
            deletes.add(RelationTuple.directMember(String.format("E%02d", i), "G"));
        }

        // when
        var hold = GuardHold.of(deletes, DirectorySnapshot.empty());

        // then
        assertThat(hold.usersGone().count()).isEqualTo(15);
        assertThat(hold.usersGone().ids()).containsExactly("E01", "E02", "E03", "E04", "E05", "E06", "E07",
                "E08", "E09", "E10");
    }

    @Test
    @DisplayName("빈 지울 줄은 모두 0이다")
    void 빈_지울_줄은_모두_0이다() {
        // when
        var hold = GuardHold.of(Set.of(), DirectorySnapshot.empty());

        // then
        assertThat(hold.totalLines()).isZero();
        assertThat(hold.directMemberLines()).isZero();
        assertThat(hold.childLines()).isZero();
        assertThat(hold.otherLines()).isZero();
        for (var sample : List.of(hold.usersGone(), hold.usersInactive(), hold.usersLosingMembership(),
                hold.groupsGone(), hold.groupsLosingParent())) {
            assertThat(sample.count()).isZero();
            assertThat(sample.ids()).isEmpty();
        }
    }

    @Test
    @DisplayName("모양이 다른 줄도 예외 없이 센다")
    void 모양이_다른_줄도_예외_없이_센다() {
        // given
        var deletes = Set.of(new RelationTuple("group:X", "direct_member", "group:G"),
                new RelationTuple("user:U", "direct_member", "G"));

        // when
        var hold = GuardHold.of(deletes, DirectorySnapshot.empty());

        // then
        assertThat(hold.directMemberLines()).isEqualTo(2);
        assertThat(hold.usersGone().ids()).containsExactly("U");
        assertThat(hold.groupsGone().ids()).containsExactly("G");
    }

    @Test
    @DisplayName("요약은 수만 담는다")
    void 요약은_수만_담는다() {
        // given
        var directory = 그림(List.of(직원("U3", true)), List.of(조직("C1")));
        var deletes = Set.of(RelationTuple.directMember("U1", "G"), RelationTuple.directMember("U3", "G"),
                RelationTuple.child("C1", "P1"));

        // when
        var summary = GuardHold.of(deletes, directory).summary();

        // then
        assertThat(summary).isEqualTo("지울 줄 3건(소속 2·하위 조직 연결 1·그 밖 0) — 직원 사라짐 1·비활성 0·소속 잃음 1 / 조직 사라짐 2·상위 잃음 1");
        assertThat(summary).doesNotContain("U1", "G", "P1");
    }

    @Test
    @DisplayName("결론의 내역이 실행 기록으로 옮겨진다")
    void 결론의_내역이_실행_기록으로_옮겨진다() {
        // given
        var hold = GuardHold.of(Set.of(RelationTuple.directMember("U", "G")), DirectorySnapshot.empty());
        var run = SyncRun.started("r", SyncSource.LDAP, SyncTrigger.SCHEDULED, Instant.EPOCH, "actor");

        // when
        var finished = run.finished(SyncOutcome.aborted("m").withGuardHold(hold), Instant.EPOCH);

        // then
        assertThat(finished.guardHold()).isEqualTo(hold);
    }

    @Test
    @DisplayName("withNote 와 withSuffix 는 내역을 보존한다")
    void withNote_와_withSuffix_는_내역을_보존한다() {
        // given
        var hold = GuardHold.of(Set.of(RelationTuple.directMember("U", "G")), DirectorySnapshot.empty());
        var outcome = SyncOutcome.aborted("m").withGuardHold(hold);

        // then
        assertThat(outcome.withNote("n").guardHold()).isSameAs(hold);
        assertThat(outcome.withSuffix("s").guardHold()).isSameAs(hold);
        assertThat(SyncOutcome.aborted("m").guardHold()).isNull();
    }
}

package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>바뀌는 멤버만 담은 그림으로 바꿔도 결과가 같다</b> (조직 멤버 PATCH 설계 §8.2).
 *
 * <p>같은 변경을 전체 목록을 비교하던 방식({@code upsertGroup})과 새 방식({@code changeGroup})으로 따로 돌려, OpenFGA 에 남는
 * 튜플과 저장된 조직이 같은지 본다. 두 방식은 각자의 세계에서 돈다. 처음 OpenFGA 는 상태와 맞춰 둔다 — 어긋남이 있으면 옛 방식은
 * 조직 전원을 고치고 새 방식은 요청에 나온 멤버만 고치므로(설계 §11) 결과가 달라지는 것이 맞다. 그 차이는
 * {@code IncrementalSyncDriftTest} 가 따로 못 박는다.
 */
class GroupChangeEquivalenceTest {

    private static final String 조직 = "DEV";

    /** 한 방식이 도는 세계. OpenFGA 의 최종 상태는 처음 심은 튜플에 쓴 것을 더하고 지운 것을 뺀 것이다. */
    private record 세계(FakeStateRepository state, FakeTupleWriter writer, FakeTupleChecker checker,
                      IncrementalSyncUseCase useCase) {

        static 세계 만든다() {
            var state = new FakeStateRepository();
            var writer = new FakeTupleWriter();
            var checker = new FakeTupleChecker();
            var useCase = new IncrementalSyncUseCase(state, writer, checker, new FakeMutationLock(),
                    Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
            var w = new 세계(state, writer, checker, useCase);
            w.직원("kim", true);
            w.직원("park", true);
            w.직원("lee", true);
            w.직원("choi", false);
            w.조직("TEAM1");
            w.조직("TEAM2");
            state.groups.put(조직, new DirectoryGroup(조직, "cn=DEV", "개발본부",
                    Set.of(MemberRef.user("kim"), MemberRef.user("park"), MemberRef.group("TEAM1"))));
            checker.allowed.add(RelationTuple.directMember("kim", 조직));
            checker.allowed.add(RelationTuple.directMember("park", 조직));
            checker.allowed.add(RelationTuple.child("TEAM1", 조직));
            return w;
        }

        void 직원(String id, boolean active) {
            state.users.put(id, new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active));
        }

        void 조직(String id, MemberRef... members) {
            state.groups.put(id, new DirectoryGroup(id, "cn=" + id, id + " 팀", Set.of(members)));
        }

        void 멤버를_더한다(MemberRef... refs) {
            DirectoryGroup dev = state.groups.get(조직);
            Set<MemberRef> members = new LinkedHashSet<>(dev.members());
            members.addAll(Set.of(refs));
            state.groups.put(조직, new DirectoryGroup(조직, dev.externalId(), dev.displayName(), members));
        }

        Set<RelationTuple> openFga() {
            Set<RelationTuple> tuples = new LinkedHashSet<>(checker.allowed);
            tuples.addAll(writer.written);
            tuples.removeAll(writer.deleted);
            return tuples;
        }

        /** 직원·하위 조직이 같은 id 로 둘 다 멤버일 때 — 현재상태에 그 조직이 있으면 하위 조직이다. */
        boolean 조직이면(String id) {
            return state.groups.containsKey(id);
        }
    }

    private static void 같은_결과다(GroupChange change) {
        같은_결과다(change, w -> {
        });
    }

    /** 같은 변경을 두 방식으로 돌려 튜플·저장 상태를 견준다. {@code 준비} 는 두 세계에 똑같이 적용한다. */
    private static void 같은_결과다(GroupChange change, Consumer<세계> 준비) {
        // given
        세계 옛 = 세계.만든다();
        세계 새 = 세계.만든다();
        준비.accept(옛);
        준비.accept(새);
        DirectoryGroup 목표 = change.applyTo(옛.state().groups.get(조직), 옛::조직이면);

        // when
        var 옛결과 = 옛.useCase().upsertGroup(목표).block(Duration.ofSeconds(10));
        var 새결과 = 새.useCase().changeGroup(조직, change).block(Duration.ofSeconds(10));

        // then
        assertThat(새결과.fullyApplied()).isEqualTo(옛결과.fullyApplied());
        assertThat(새.openFga()).containsExactlyInAnyOrderElementsOf(옛.openFga());
        assertThat(새.state().groups.get(조직)).isEqualTo(옛.state().groups.get(조직));
    }

    @Test
    @DisplayName("활성 직원을 넣는다")
    void 활성_직원_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("lee"))));
    }

    @Test
    @DisplayName("비활성 직원을 넣으면 멤버십만 생기고 튜플은 없다")
    void 비활성_직원_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("choi"))));
    }

    @Test
    @DisplayName("아직 없는 직원을 넣으면 멤버 줄만 남는다")
    void 없는_직원_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("ghost"))));
    }

    @Test
    @DisplayName("이미 멤버인 직원을 넣으면 아무것도 바뀌지 않는다")
    void 이미_멤버_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("kim"))));
    }

    @Test
    @DisplayName("멤버를 id 로 뺀다")
    void 멤버_빼기() {
        같은_결과다(GroupChange.delta().removingId("park"));
    }

    @Test
    @DisplayName("멤버가 아닌 id 를 빼면 아무것도 바뀌지 않는다")
    void 비멤버_빼기() {
        같은_결과다(GroupChange.delta().removingId("lee"));
    }

    @Test
    @DisplayName("하위 조직을 넣으면 child 엣지가 생긴다")
    void 하위_조직_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.group("TEAM2"))));
    }

    @Test
    @DisplayName("아직 없는 하위 조직을 넣으면 멤버 줄만 남는다")
    void 없는_하위_조직_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.group("GHOST"))));
    }

    @Test
    @DisplayName("순환을 만드는 하위 조직은 엣지를 버리고 멤버십은 남긴다")
    void 순환_하위_조직() {
        // TEAM2 → MID → DEV 인데 DEV 아래에 TEAM2 를 넣는다. 두 홉으로 둔다 — 한 홉(TEAM2 → DEV)이면 옛 방식은 상위 조직
        // TEAM2 를 멤버째 스냅샷에 실어 TupleMapper 의 DFS 가 어느 간선을 버릴지가 순서에 달린다
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.group("TEAM2"))), w -> {
            w.조직("MID", MemberRef.group(조직));
            w.조직("TEAM2", MemberRef.group("MID"));
            w.checker().allowed.add(RelationTuple.child(조직, "MID"));
            w.checker().allowed.add(RelationTuple.child("MID", "TEAM2"));
        });
    }

    @Test
    @DisplayName("직원과 하위 조직이 같은 id 로 둘 다 멤버면 id 빼기는 현재상태로 한쪽만 뺀다")
    void 같은_id() {
        같은_결과다(GroupChange.delta().removingId("X"), w -> {
            w.직원("X", true);
            w.조직("X");
            w.멤버를_더한다(MemberRef.user("X"), MemberRef.group("X"));
            w.checker().allowed.add(RelationTuple.directMember("X", 조직));
            w.checker().allowed.add(RelationTuple.child("X", 조직));
        });
    }

    @Test
    @DisplayName("OpenFGA 쓰기·삭제가 일부 실패하면 반영된 멤버만 저장된다")
    void 일부_실패() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("lee"))).removingId("park"),
                w -> w.writer().failFor(tuple -> tuple.equals(RelationTuple.directMember("lee", 조직))
                        || tuple.equals(RelationTuple.directMember("park", 조직))));
    }

    @Test
    @DisplayName("전체 교체 — 빠지는 멤버와 들어오는 멤버")
    void 전체_교체() {
        같은_결과다(GroupChange.delta().replacing(Set.of(MemberRef.user("kim"), MemberRef.user("lee"))));
    }

    @Test
    @DisplayName("전체 교체에서 OpenFGA 쓰기·삭제가 일부 실패하면 반영된 멤버만 저장된다")
    void 전체_교체_일부_실패() {
        같은_결과다(GroupChange.delta().replacing(Set.of(MemberRef.user("kim"), MemberRef.user("lee"))),
                w -> w.writer().failFor(tuple -> tuple.equals(RelationTuple.directMember("lee", 조직))
                        || tuple.equals(RelationTuple.directMember("park", 조직))));
    }

    @Test
    @DisplayName("전체 비우기")
    void 전체_비우기() {
        같은_결과다(GroupChange.delta().replacing(Set.of()));
    }

    @Test
    @DisplayName("PUT — 이름·externalId·멤버를 통째로 바꾼다")
    void PUT() {
        같은_결과다(GroupChange.replacement("cn=DEV-2", "개발본부2",
                Set.of(MemberRef.user("park"), MemberRef.group("TEAM2"))));
    }

    @Test
    @DisplayName("이름만 바꾼다")
    void 이름만() {
        같은_결과다(GroupChange.delta().renamed("플랫폼본부"));
    }

    @Test
    @DisplayName("전체 교체 뒤의 연산은 목표 목록에 적용된다")
    void 교체_뒤_연산() {
        같은_결과다(GroupChange.delta()
                .replacing(Set.of(MemberRef.user("kim")))
                .adding(Set.of(MemberRef.user("lee")))
                .removingId("kim"));
    }
}

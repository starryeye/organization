package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 위로 올라가는 순환 검사와 보류 목록(설계 2026-10-03 §4.2~§4.4, 점검 P2·S4). */
class IncrementalSyncCycleTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    /** checker.allowed 에는 각 테스트가 OpenFGA 에 있다고 볼 튜플을 넣는다. */
    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, "백엔드팀", Set.of(members));
    }

    @Test
    @DisplayName("순환을 닫는 연결은 쓰지 않고 보류 목록에 적는다")
    void 순환을_닫는_연결은_보류한다() {
        // given — A ⊃ B ⊃ C
        state.groups.put("C", 조직("C"));
        state.groups.put("B", 조직("B", MemberRef.group("C")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        checker.allowed.addAll(Set.of(RelationTuple.child("C", "B"), RelationTuple.child("B", "A")));

        // when — C 에 A 를 넣어 A → B → C → A 를 닫으려 한다
        var result = useCase.changeGroup("C", GroupChange.delta().adding(Set.of(MemberRef.group("A")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).doesNotContain(RelationTuple.child("A", "C"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("C", "A"));
        assertThat(state.groups.get("C").members()).contains(MemberRef.group("A"));
    }

    @Test
    @DisplayName("조직이 자기 자신을 하위 조직으로 넣으면 그 연결을 보류한다")
    void 자기_자신을_넣는_연결은_보류한다() {
        // given
        state.groups.put("DEV", 조직("DEV"));

        // when
        var result = useCase.changeGroup("DEV", GroupChange.delta().adding(Set.of(MemberRef.group("DEV")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).isEmpty();
        assertThat(state.cutEdges).containsExactly(new GroupEdge("DEV", "DEV"));
    }

    @Test
    @DisplayName("순환 검사는 자손을 읽지 않고 부모에서 위로 올라간다")
    void 자손을_읽지_않는다() {
        // given — HQ 아래 자손 200개, 새 상위 NEWTOP
        Set<MemberRef> 부서 = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            Set<MemberRef> 팀 = new LinkedHashSet<>();
            for (int j = 0; j < 10; j++) {
                state.groups.put("T" + i + "_" + j, 조직("T" + i + "_" + j));
                팀.add(MemberRef.group("T" + i + "_" + j));
            }
            state.groups.put("D" + i, new DirectoryGroup("D" + i, "D" + i, "부서", 팀));
            부서.add(MemberRef.group("D" + i));
        }
        state.groups.put("HQ", new DirectoryGroup("HQ", "HQ", "본부", 부서));
        state.groups.put("NEWTOP", 조직("NEWTOP"));
        state.findGroupIdsContainingCalls.clear();

        // when
        var result = useCase.changeGroup("NEWTOP", GroupChange.delta().adding(Set.of(MemberRef.group("HQ")))).block();

        // then — NEWTOP 의 조상만 본다(없음). 자손 220개를 읽지 않는다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("HQ", "NEWTOP"));
        assertThat(state.findGroupIdsContainingCalls).containsExactly("NEWTOP");
    }

    @Test
    @DisplayName("이미 보류한 연결을 지나는 경로는 순환으로 보지 않는다 — 튜플 그래프로 본다")
    void 보류한_연결을_지나는_경로는_순환이_아니다() {
        // given — 멤버 줄 B ⊃ A 는 보류돼 튜플이 없다
        state.groups.put("A", 조직("A"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));

        // when — A 에 B 를 넣는다. 멤버 줄로는 B → A 가 있어 순환처럼 보이지만 튜플 그래프에는 없다
        var result = useCase.changeGroup("A", GroupChange.delta().adding(Set.of(MemberRef.group("B")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("B", "A"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("B", "A"));
    }

    @Test
    @DisplayName("한 홉 순환이 있는 새 조직 POST 는 먼저 저장된 연결이 남고 새 연결이 보류된다(점검 S4)")
    void 한_홉_순환은_먼저_저장된_연결이_이긴다() {
        // given — B 가 아직 없는 A 를 하위 조직으로 적어 두었다
        state.groups.put("B", 조직("B", MemberRef.group("A")));

        // when — A 를 만들며 B 를 하위 조직으로 넣는다
        var result = useCase.createGroup(조직("A", MemberRef.group("B"))).block();

        // then — "A 는 B 의 하위"가 남고 "B 는 A 의 하위"가 보류된다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("A", "B")).doesNotContain(RelationTuple.child("B", "A"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("A", "B"));
    }

    @Test
    @DisplayName("한 홉 순환의 결과는 조직코드 순서와 무관하다")
    void 한_홉_순환은_조직코드_순서와_무관하다() {
        // given — 이름 순서를 뒤집는다: A 가 아직 없는 Z 를 적어 두었다
        state.groups.put("A", 조직("A", MemberRef.group("Z")));

        // when
        var result = useCase.createGroup(조직("Z", MemberRef.group("A"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("Z", "A")).doesNotContain(RelationTuple.child("A", "Z"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("Z", "A"));
    }

    @Test
    @DisplayName("새로 보류할 줄은 멤버 줄보다 먼저 쓴다")
    void 보류_줄을_먼저_쓴다() {
        // given
        state.groups.put("A", 조직("A"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        checker.allowed.add(RelationTuple.child("A", "B"));
        state.쓴순서.clear();

        // when — A 에 B 를 넣어 순환을 닫는다
        useCase.changeGroup("A", GroupChange.delta().adding(Set.of(MemberRef.group("B")))).block();

        // then
        assertThat(state.쓴순서).containsSubsequence("보류+", "saveGroupChange:A");
    }

    @Test
    @DisplayName("보류했던 연결을 요청이 다시 언급하고 이제 순환이 아니면 쓰고 목록에서 뺀다")
    void 다시_언급된_보류_연결은_순환이_풀렸으면_쓴다() {
        // given — 멤버 줄 B ⊃ A 가 보류돼 있고, 순환을 만들던 A ⊃ B 는 이미 없다
        state.groups.put("A", 조직("A"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));

        // when — B 에 A 를 다시 넣는다(이미 멤버)
        var result = useCase.changeGroup("B", GroupChange.delta().adding(Set.of(MemberRef.group("A")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("OpenFGA 에 있는 연결은 보류 목록에 남아 있어도 튜플 그래프에 있다 — 그 연결을 지나는 순환을 닫는 새 연결을 보류한다")
    void OpenFGA에_있는_보류_연결도_그래프에_있다() {
        // given — X ⊃ G 는 OpenFGA 에 있는데 보류 목록에도 남았다(쓰기 뒤 목록에서 빼기 전에 멈춘 경우)
        state.groups.put("G", 조직("G"));
        state.groups.put("X", 조직("X", MemberRef.group("G")));
        checker.allowed.add(RelationTuple.child("G", "X"));
        state.cutEdges.add(new GroupEdge("X", "G"));

        // when — G 에 X 를 넣는다. G ⊃ X 는 OpenFGA 에 있는 X ⊃ G 와 순환을 닫는다
        useCase.upsertGroup(조직("G", MemberRef.group("X"))).block();

        // then — 새 연결은 보류하고, OpenFGA 에 있는 X ⊃ G 는 목록에서 뺀다
        assertThat(writer.written).doesNotContain(RelationTuple.child("X", "G"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("G", "X"));
    }

    @Test
    @DisplayName("상태 기준선은 보류한 연결을 뺀다 — 보류 연결이 OpenFGA 에 없는 것을 어긋남으로 세지 않는다")
    void 보류_연결은_어긋남이_아니다() {
        // given
        List<int[]> 관측 = new ArrayList<>();
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO,
                (extra, missing) -> 관측.add(new int[]{extra, missing}), LockObserver.NOOP);
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.add(RelationTuple.child("B", "A"));

        // when — 같은 멤버로 다시 저장(전체 비교 경로)
        useCase.upsertGroup(조직("A", MemberRef.group("B"))).block();

        // then
        assertThat(관측).isEmpty();
    }

    @Test
    @DisplayName("하위 조직 연결이 없는 요청은 보류 목록을 읽지 않는다")
    void 직원만_바뀌면_보류_목록을_읽지_않는다() {
        // given
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));
        state.groups.put("DEV", 조직("DEV"));

        // when
        useCase.changeGroup("DEV", GroupChange.delta().adding(Set.of(MemberRef.user("kim")))).block();

        // then
        assertThat(state.findCutEdgesCalls).isZero();
    }

    @Test
    @DisplayName("조상이 한도를 넘으면 추측하지 않고 GroupGraphTooLargeException 으로 끝난다 — 아무것도 쓰지 않는다")
    void 한도를_넘으면_예외다() {
        // given — G1 ⊃ G2 ⊃ … ⊃ G10101 (G10101 의 조상이 10,100개)
        int 깊이 = OrgGraph.MAX_EXPANSIONS + 100;
        for (int i = 1; i <= 깊이; i++) {
            state.groups.put("G" + i, 조직("G" + i, MemberRef.group("G" + (i + 1))));
        }
        state.groups.put("G" + (깊이 + 1), 조직("G" + (깊이 + 1)));
        state.groups.put("NEW", 조직("NEW"));

        // when — 맨 아래에 새 하위 조직을 붙이면 위로 사슬 전체를 올라가야 한다
        var 실행 = useCase.changeGroup("G" + (깊이 + 1), GroupChange.delta().adding(Set.of(MemberRef.group("NEW"))));

        // then
        assertThatThrownBy(실행::block)
                .isInstanceOf(GroupGraphTooLargeException.class)
                .hasMessageContaining("조직 계층이 너무 크다");
        assertThat(writer.appliedDeltas).isEmpty();
    }
}

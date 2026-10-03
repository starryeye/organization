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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 순환이 풀리면 보류했던 연결을 쓴다(설계 2026-10-03 §4.5, 점검 M1). 셋업·도우미는 IncrementalSyncCycleTest 와 같다. */
class IncrementalSyncCycleRestoreTest {

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
    @DisplayName("점검 재현 — 본부 ⊃ A ⊃ B 에서 B 를 A 위로 올리는 순서가 꼬여도 결국 'A 는 B 의 하위'가 쓰인다")
    void 순환이_풀리면_보류했던_연결을_쓴다() {
        // given — 본부 ⊃ A ⊃ B
        state.groups.put("B", 조직("B"));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.groups.put("HQ", 조직("HQ", MemberRef.group("A")));
        checker.allowed.addAll(Set.of(RelationTuple.child("B", "A"), RelationTuple.child("A", "HQ")));

        // when — 개편: B 에 A 를 넣고(순환이라 보류), 그다음 A 에서 B 를 뺀다
        useCase.changeGroup("B", GroupChange.delta().adding(Set.of(MemberRef.group("A")))).block();
        var 보류된것 = Set.copyOf(state.cutEdges);
        var result = useCase.changeGroup("A", GroupChange.delta().removingId("B")).block();

        // then
        assertThat(보류된것).containsExactly(new GroupEdge("B", "A"));
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("멤버 줄이 없는 보류 줄은 다음 지우기 요청 끝에서 지운다")
    void 멤버_줄이_없는_보류_줄은_지운다() {
        // given — 이미 지워진 조직 GONE 의 보류 줄이 남아 있다
        state.cutEdges.add(new GroupEdge("GONE", "X"));
        state.groups.put("X", 조직("X"));
        state.groups.put("P", 조직("P", MemberRef.group("Q")));
        state.groups.put("Q", 조직("Q"));
        checker.allowed.add(RelationTuple.child("Q", "P"));

        // when — 아무 하위 조직 빼기
        useCase.changeGroup("P", GroupChange.delta().removingId("Q")).block();

        // then
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("아직 순환이면 보류 줄을 그대로 둔다")
    void 아직_순환이면_그대로_둔다() {
        // given — A ⊃ B ⊃ C 이고 C ⊃ A 가 보류돼 있다. 상관없는 D 를 B 에서 뺀다
        state.groups.put("C", 조직("C", MemberRef.group("A")));
        state.groups.put("D", 조직("D"));
        state.groups.put("B", 조직("B", MemberRef.group("C"), MemberRef.group("D")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.cutEdges.add(new GroupEdge("C", "A"));
        checker.allowed.addAll(Set.of(RelationTuple.child("B", "A"), RelationTuple.child("C", "B"), RelationTuple.child("D", "B")));

        // when
        useCase.changeGroup("B", GroupChange.delta().removingId("D")).block();

        // then
        assertThat(writer.written).doesNotContain(RelationTuple.child("A", "C"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("C", "A"));
    }

    @Test
    @DisplayName("한 요청이 하위 조직 하나를 빼고 다른 하나를 넣어도 끝에서 다시 본다")
    void 빼고_넣는_요청도_끝에서_다시_본다() {
        // given — A ⊃ B, B ⊃ A 는 보류. A 에서 B 를 빼며 E 를 넣는다(전체 교체)
        state.groups.put("E", 조직("E"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.add(RelationTuple.child("B", "A"));

        // when
        var result = useCase.changeGroup("A", GroupChange.replacement("A", "A", Set.of(MemberRef.group("E")))).block();

        // then — E 는 지금 그래프로 검사돼 쓰이고, 순환이 풀린 B ⊃ A 도 쓰인다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("E", "A"), RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("다시 검사의 쓰기가 실패해도 원래 요청은 성공하고 보류 줄은 남는다")
    void 다시_검사가_실패해도_원래_요청은_성공한다() {
        // given — 첫 테스트와 같은 개편, 되살릴 쓰기만 실패한다
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.add(RelationTuple.child("B", "A"));
        writer.failFor(tuple -> tuple.equals(RelationTuple.child("A", "B")));

        // when
        var result = useCase.changeGroup("A", GroupChange.delta().removingId("B")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.cutEdges).containsExactly(new GroupEdge("B", "A"));
    }

    @Test
    @DisplayName("직원만 빼는 요청은 보류 목록을 읽지 않는다")
    void 직원만_빼면_다시_보지_않는다() {
        // given
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));
        state.groups.put("DEV", 조직("DEV", MemberRef.user("kim")));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV"));

        // when
        useCase.changeGroup("DEV", GroupChange.delta().removingId("kim")).block();

        // then
        assertThat(state.findCutEdgesCalls).isZero();
    }

    @Test
    @DisplayName("조직을 지운 뒤에도 다시 본다 — 그 조직이 막던 순환이 풀린다")
    void 조직_삭제_뒤에도_다시_본다() {
        // given — A ⊃ M ⊃ B 이고 B ⊃ A 가 보류돼 있다. M 을 지우면 순환이 풀린다
        state.groups.put("M", 조직("M", MemberRef.group("B")));
        state.groups.put("A", 조직("A", MemberRef.group("M")));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.addAll(Set.of(RelationTuple.child("M", "A"), RelationTuple.child("B", "M")));

        // when
        useCase.removeGroup("M").block();

        // then
        assertThat(writer.written).contains(RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }
}

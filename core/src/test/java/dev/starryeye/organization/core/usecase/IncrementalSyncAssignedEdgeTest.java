package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
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

/** IdP 쓰기 경로가 지정 연결을 원천으로 본다 — 하위 조직 빼기·조직 지우기·순환 판정·보류 다시 보기(설계 2026-11-04 H5·H6, §5.3~§5.6). */
class IncrementalSyncAssignedEdgeTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    /** checker.allowed 에는 각 테스트가 OpenFGA 에 있다고 볼 튜플을, state.assignedParents 에는 지정 연결(하위 → 상위)을 넣는다. */
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
    @DisplayName("IdP 가 하위 조직을 빼도 그 조직의 지정 상위가 이 조직이면 child 줄은 남고 멤버 행만 빠진다")
    void IdP_가_하위_조직을_빼도_지정이_받치면_줄이_남는다() {
        // given — P ⊃ C 가 멤버 행과 지정 둘 다로 있다
        state.groups.put("C", 조직("C"));
        state.groups.put("P", 조직("P", MemberRef.group("C")));
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));

        // when
        var result = useCase.changeGroup("P", GroupChange.delta().removingId("C")).block();

        // then — 이미 있는 줄을 다시 쓰지도 않는다(그림에 싣지 않는다)
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).doesNotContain(RelationTuple.child("C", "P"));
        assertThat(writer.written).isEmpty();
        assertThat(state.groups.get("P").members()).doesNotContain(MemberRef.group("C"));
    }

    @Test
    @DisplayName("IdP 가 멤버를 빈 목록으로 통째로 바꿔도 지정이 받치는 child 줄은 남고 멤버 행은 되살아나지 않는다")
    void IdP_가_멤버를_통째로_비워도_지정이_받치면_줄이_남는다() {
        // given
        state.groups.put("C", 조직("C"));
        state.groups.put("P", 조직("P", MemberRef.group("C")));
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));

        // when
        var result = useCase.changeGroup("P", GroupChange.delta().replacing(Set.of())).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).doesNotContain(RelationTuple.child("C", "P"));
        assertThat(state.groups.get("P").members()).doesNotContain(MemberRef.group("C"));
    }

    @Test
    @DisplayName("지정 연결이 없으면 빠지는 하위 조직의 child 줄을 지금처럼 지운다")
    void 지정이_없으면_지금처럼_지운다() {
        // given
        state.groups.put("C", 조직("C"));
        state.groups.put("P", 조직("P", MemberRef.group("C")));
        checker.allowed.add(RelationTuple.child("C", "P"));

        // when
        var result = useCase.changeGroup("P", GroupChange.delta().removingId("C")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).contains(RelationTuple.child("C", "P"));
        assertThat(state.groups.get("P").members()).doesNotContain(MemberRef.group("C"));
    }

    @Test
    @DisplayName("빠지는 하위 조직의 지정 상위가 다른 조직이면 이 조직과의 child 줄을 지운다")
    void 지정이_다른_상위를_가리키면_지운다() {
        // given — C 의 지정 상위는 Q 다
        state.groups.put("C", 조직("C"));
        state.groups.put("Q", 조직("Q"));
        state.groups.put("P", 조직("P", MemberRef.group("C")));
        state.assignedParents.put("C", "Q");
        checker.allowed.addAll(Set.of(RelationTuple.child("C", "P"), RelationTuple.child("C", "Q")));

        // when
        var result = useCase.changeGroup("P", GroupChange.delta().removingId("C")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).contains(RelationTuple.child("C", "P"));
        assertThat(writer.deleted).doesNotContain(RelationTuple.child("C", "Q"));
        assertThat(state.groups.get("P").members()).doesNotContain(MemberRef.group("C"));
    }

    @Test
    @DisplayName("조직을 지우면 그 조직의 지정 상위와의 줄, 그 조직을 상위로 지정한 하위와의 줄도 지우고 지정 아이템을 정리한다")
    void 조직을_지우면_지정_연결의_줄도_지운다() {
        // given — 멤버 행 없이 지정만: P ⊃ G ⊃ C
        state.groups.put("P", 조직("P"));
        state.groups.put("G", 조직("G"));
        state.groups.put("C", 조직("C"));
        state.assignedParents.put("G", "P");
        state.assignedParents.put("C", "G");
        checker.allowed.addAll(Set.of(RelationTuple.child("G", "P"), RelationTuple.child("C", "G")));

        // when
        var result = useCase.removeGroup("G").block();

        // then — 지정 연결을 지웠으니 끝에서 보류 목록을 다시 본다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).contains(RelationTuple.child("G", "P"), RelationTuple.child("C", "G"));
        assertThat(state.assignedParents).doesNotContainKeys("G", "C");
        assertThat(state.groups).doesNotContainKey("G");
        assertThat(state.findCutEdgesCalls).isPositive();
    }

    @Test
    @DisplayName("조직 지우기가 일부 실패하면 줄을 지운 지정 연결의 아이템만 지우고 못 지운 것은 남긴다 — 조직도 남는다")
    void 조직_지우기가_일부_실패하면_못_지운_지정은_남는다() {
        // given
        state.groups.put("P", 조직("P"));
        state.groups.put("G", 조직("G"));
        state.groups.put("C", 조직("C"));
        state.assignedParents.put("G", "P");
        state.assignedParents.put("C", "G");
        checker.allowed.addAll(Set.of(RelationTuple.child("G", "P"), RelationTuple.child("C", "G")));
        writer.failFor(tuple -> tuple.equals(RelationTuple.child("C", "G")));

        // when
        var result = useCase.removeGroup("G").block();

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.assignedParents).containsEntry("C", "G");
        assertThat(state.assignedParents).doesNotContainKey("G");
        assertThat(state.groups).containsKey("G");
    }

    @Test
    @DisplayName("IdP 가 보낸 연결이 지정 연결과 순환을 닫으면 그 연결을 쓰지 않고 보류한다 — 먼저 저장된 지정이 이긴다")
    void IdP_연결이_지정_연결과_순환을_닫으면_보류한다() {
        // given — 지정 P ⊃ C
        state.groups.put("C", 조직("C"));
        state.groups.put("P", 조직("P"));
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));

        // when — IdP 가 C 에 P 를 넣어 C ⊃ P ⊃ C 를 닫으려 한다
        var result = useCase.changeGroup("C", GroupChange.delta().adding(Set.of(MemberRef.group("P")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.cutEdges).containsExactly(new GroupEdge("C", "P"));
        assertThat(writer.written).doesNotContain(RelationTuple.child("P", "C"));
    }

    @Test
    @DisplayName("보류 다시 보기는 멤버 행이 없어도 지정 연결이 있으면 원천이 있다고 보고, 순환이 풀렸으면 쓴다")
    void 보류_다시_보기는_지정을_원천으로_본다() {
        // given — 지정 P ⊃ C 는 보류돼 튜플이 없다. X ⊃ Y 는 관계없는 하위 조직 연결이다
        state.groups.put("Y", 조직("Y"));
        state.groups.put("X", 조직("X", MemberRef.group("Y")));
        state.groups.put("P", 조직("P"));
        state.groups.put("C", 조직("C"));
        state.assignedParents.put("C", "P");
        state.cutEdges.add(new GroupEdge("P", "C"));
        checker.allowed.add(RelationTuple.child("Y", "X"));

        // when — 하위 조직 연결을 지우는 요청이 끝에서 보류 목록을 다시 본다
        useCase.changeGroup("X", GroupChange.delta().removingId("Y")).block();

        // then
        assertThat(writer.written).contains(RelationTuple.child("C", "P"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("보류 다시 보기는 멤버 행도 지정 연결도 없는 보류 줄을 쓰지 않고 버린다")
    void 보류_다시_보기는_원천이_모두_없으면_버린다() {
        // given — 위와 같고 지정이 없다
        state.groups.put("Y", 조직("Y"));
        state.groups.put("X", 조직("X", MemberRef.group("Y")));
        state.groups.put("P", 조직("P"));
        state.groups.put("C", 조직("C"));
        state.cutEdges.add(new GroupEdge("P", "C"));
        checker.allowed.add(RelationTuple.child("Y", "X"));

        // when
        useCase.changeGroup("X", GroupChange.delta().removingId("Y")).block();

        // then
        assertThat(state.cutEdges).isEmpty();
        assertThat(writer.written).doesNotContain(RelationTuple.child("C", "P"));
    }
}

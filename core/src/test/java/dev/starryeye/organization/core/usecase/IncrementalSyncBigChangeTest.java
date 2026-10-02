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
import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큰 변경을 싸게 — 조직 삭제는 계산 없이, 빠지는 멤버는 Check 없이 지운다 (설계 2026-10-02 §4.1·§4.2, 점검 C6).
 */
class IncrementalSyncBigChangeTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        useCase = new IncrementalSyncUseCase(state, writer, checker, new FakeMutationLock(), Duration.ZERO,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        // 본부(HQ) ⊃ 팀(TEAM) ⊃ kim(활성)·lee(비활성)·하위 조직 SUB
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", false)).block();
        state.saveGroup(new DirectoryGroup("SUB", "SUB", "하위", Set.of(MemberRef.user("kim")))).block();
        state.saveGroup(new DirectoryGroup("TEAM", "TEAM", "팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.group("SUB")))).block();
        state.saveGroup(new DirectoryGroup("HQ", "HQ", "본부", Set.of(MemberRef.group("TEAM")))).block();
        checker.allowed.addAll(TupleMapper.toTuples(state.loadAll().block()).tuples());
        state.findUserCalls.clear();
        state.findGroupCalls.clear();
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", active);
    }

    @Test
    @DisplayName("조직 삭제는 직원을 읽지 않고 Check 하지 않으며, 그 조직을 언급하는 줄을 '없으면 무시'로 다 지운다")
    void 조직_삭제는_계산하지_않는다() {
        // when
        var result = useCase.removeGroup("TEAM").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findUserCalls).as("멤버 직원을 읽지 않는다").isEmpty();
        assertThat(checker.checked).as("Check 하지 않는다").isEmpty();
        assertThat(writer.deleted).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "TEAM"),
                RelationTuple.directMember("lee", "TEAM"),
                RelationTuple.child("SUB", "TEAM"),
                RelationTuple.child("TEAM", "HQ"));
        assertThat(state.groups).doesNotContainKey("TEAM");
        assertThat(state.groups.get("HQ").members()).isEmpty();
        assertThat(state.groups.get("SUB").members()).as("하위 조직 자신의 멤버는 그대로").containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("상위 조직은 아이디만 읽는다 — 큰 본부 밑의 작은 팀을 지울 때 본부 멤버 목록을 읽지 않는다")
    void 상위_조직은_아이디만_읽는다() {
        // when
        useCase.removeGroup("TEAM").block();

        // then
        assertThat(state.findGroupCalls).as("지우는 조직만 한 번 읽는다").containsExactly("TEAM");
        assertThat(state.deleteGroupCalls).containsExactly("TEAM");
    }

    @Test
    @DisplayName("멤버도 상위 조직도 없는 조직도 지운다")
    void 빈_조직도_지운다() {
        // given
        state.saveGroup(new DirectoryGroup("EMPTY", "EMPTY", "빈 조직", Set.of())).block();

        // when
        var result = useCase.removeGroup("EMPTY").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.groups).doesNotContainKey("EMPTY");
    }

    @Test
    @DisplayName("일부 줄을 못 지우면 조직을 남기고, 지운 멤버·상위 조직 줄만 뺀다 — 재시도가 남은 것을 지운다")
    void 일부를_못_지우면_조직을_남긴다() {
        // given
        writer.failFor(tuple -> tuple.equals(RelationTuple.directMember("kim", "TEAM")));

        // when
        var result = useCase.removeGroup("TEAM").block();

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.groups.get("TEAM").members()).containsExactly(MemberRef.user("kim"));
        assertThat(state.groups.get("HQ").members()).as("상위 조직 줄은 지워졌다").isEmpty();
    }

    @Test
    @DisplayName("멤버 전원 빼기는 빠지는 멤버를 Check·직원 읽기 없이 '없으면 무시'로 지운다")
    void 전원_빼기는_확인하지_않는다() {
        // when
        var result = useCase.changeGroup("TEAM", GroupChange.delta().replacing(Set.of())).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findUserCalls).isEmpty();
        assertThat(checker.checked).isEmpty();
        assertThat(writer.deleted).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "TEAM"),
                RelationTuple.directMember("lee", "TEAM"),
                RelationTuple.child("SUB", "TEAM"));
        assertThat(state.groups.get("TEAM").members()).isEmpty();
    }

    @Test
    @DisplayName("교체에서 들어오는 멤버는 지금처럼 Check 하고, 빠지는 멤버는 Check 하지 않는다")
    void 들어오는_쪽만_확인한다() {
        // given
        state.saveUser(직원("park", true)).block();
        state.findUserCalls.clear();

        // when — kim·lee·SUB 가 빠지고 park 이 들어온다
        useCase.changeGroup("TEAM", GroupChange.delta().replacing(Set.of(MemberRef.user("park")))).block();

        // then
        assertThat(checker.checked).containsExactly(RelationTuple.directMember("park", "TEAM"));
        assertThat(state.findUserCalls).containsOnly("park");
        assertThat(writer.written).containsExactly(RelationTuple.directMember("park", "TEAM"));
        assertThat(state.groups.get("TEAM").members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("빠지는 멤버의 줄을 못 지우면 그 멤버는 남는다")
    void 빠지는_멤버를_못_지우면_남는다() {
        // given
        writer.failFor(tuple -> tuple.equals(RelationTuple.directMember("lee", "TEAM")));

        // when
        var result = useCase.changeGroup("TEAM", GroupChange.delta().replacing(Set.of())).block();

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.groups.get("TEAM").members()).containsExactly(MemberRef.user("lee"));
    }
}

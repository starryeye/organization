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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>조직 멤버 변경이 조직 크기만큼 읽지 않는다</b> (조직 멤버 PATCH 설계 §8.2).
 *
 * <p>되돌려도 튜플은 맞고 E2E 도 통과한다 — 여기서만 잡힌다. {@code IncrementalSyncReadScopeTest} 가 직원 쪽을 같은 방식으로 본다.
 */
class GroupChangeReadScopeTest {

    private static final int 멤버수 = 5_000;
    private static final String 대형조직 = "ALL";

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private IncrementalSyncUseCase useCase;
    private Set<MemberRef> 전원;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        useCase = new IncrementalSyncUseCase(state, writer, checker, new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        전원 = new LinkedHashSet<>();
        IntStream.range(0, 멤버수).forEach(i -> {
            String id = "u" + i;
            state.users.put(id, 직원(id));
            전원.add(MemberRef.user(id));
            checker.allowed.add(RelationTuple.directMember(id, 대형조직));
        });
        state.users.put("newbie", 직원("newbie"));
        state.groups.put(대형조직, new DirectoryGroup(대형조직, "cn=ALL", "전 직원", 전원));

        state.findGroupCalls.clear();
        state.findGroupHeaderCalls.clear();
        state.findUserCalls.clear();
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", true);
    }

    @Test
    @DisplayName("한 명을 넣을 때 조직 전체도 동료도 읽지 않는다")
    void 한명_추가() {
        // when
        var result = useCase.changeGroup(대형조직, GroupChange.delta().adding(Set.of(MemberRef.user("newbie"))))
                .block(Duration.ofSeconds(10));

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findGroupCalls).as("조직 파티션을 통째로 읽지 않는다").isEmpty();
        assertThat(state.findMemberRefsCalls).isEmpty();
        assertThat(state.findMembersCalls).containsExactly(Set.of(MemberRef.user("newbie")));
        assertThat(state.findUserCalls).as("직원을 한 명씩 읽지 않는다").isEmpty();
        assertThat(state.findUsersCalls).containsExactly(Set.of("newbie"));
        assertThat(checker.checked).containsOnly(RelationTuple.directMember("newbie", 대형조직));
        assertThat(state.groups.get(대형조직).members()).hasSize(멤버수 + 1);
    }

    @Test
    @DisplayName("한 명을 뺄 때도 그 한 명만 본다")
    void 한명_빼기() {
        // when
        useCase.changeGroup(대형조직, GroupChange.delta().removingId("u0")).block(Duration.ofSeconds(10));

        // then
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findMembersCalls).containsExactly(Set.of(MemberRef.user("u0"), MemberRef.group("u0")));
        assertThat(state.findUserCalls).as("빠지는 멤버는 읽지 않는다").isEmpty();
        assertThat(checker.checked).as("빠지는 멤버는 Check 하지 않는다").isEmpty();
        assertThat(writer.deleted).containsExactly(RelationTuple.directMember("u0", 대형조직));
        assertThat(state.groups.get(대형조직).members()).hasSize(멤버수 - 1);
    }

    @Test
    @DisplayName("전체 교체는 멤버 키를 한 번 읽고 바뀐 멤버만 본다")
    void 전체_교체() {
        // given — 한 명 빠지고 한 명 들어온 목록
        Set<MemberRef> 목표 = new LinkedHashSet<>(전원);
        목표.remove(MemberRef.user("u0"));
        목표.add(MemberRef.user("newbie"));

        // when
        useCase.changeGroup(대형조직, GroupChange.delta().replacing(목표)).block(Duration.ofSeconds(10));

        // then
        assertThat(state.findMemberRefsCalls).containsExactly(대형조직);
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findUserCalls).as("직원을 한 명씩 읽지 않는다").isEmpty();
        assertThat(state.findUsersCalls).containsExactly(Set.of("newbie"));
        assertThat(checker.checked).containsOnly(RelationTuple.directMember("newbie", 대형조직));
        assertThat(state.groups.get(대형조직).members()).isEqualTo(목표);
    }

    @Test
    @DisplayName("들어오는 멤버가 많아도 직원을 한 번에 묶어 읽는다")
    void 많이_넣어도_묶어_읽는다() {
        // given — 아직 멤버가 아닌 직원 300명
        Set<MemberRef> 새멤버 = new LinkedHashSet<>();
        IntStream.range(0, 300).forEach(i -> {
            state.users.put("n" + i, 직원("n" + i));
            새멤버.add(MemberRef.user("n" + i));
        });

        // when
        var result = useCase.changeGroup(대형조직, GroupChange.delta().adding(새멤버)).block(Duration.ofSeconds(10));

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findUserCalls).isEmpty();
        assertThat(state.findUsersCalls).hasSize(1);
        assertThat(state.findUsersCalls.get(0)).hasSize(300);
    }
}

package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
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
 * <b>직원 한 명에 대한 연산이 조직 크기만큼 읽지 않는다.</b>
 *
 * <p>{@link IncrementalSyncCandidateScopeTest} 가 <b>OpenFGA 쪽</b>(무엇을 Check 하는가)을
 * 못박는다면, 이 테스트는 <b>DynamoDB 쪽</b>(무엇을 읽는가)을 못박는다. 둘은 다른 질문이고,
 * 전자만 고쳐 놓은 채로 후자가 오래 살아 있었다 — 결과가 맞아서 어떤 테스트도 묻지 않았다.
 *
 * <p><b>이 테스트가 없으면 이 최적화는 조용히 되돌아간다.</b> 되돌려도 튜플은 여전히 맞고
 * 규모 E2E 도 전부 통과하기 때문이다. 여기서만 잡힌다.
 */
class IncrementalSyncReadScopeTest {

    /** 조직 크기에 비례하는 읽기가 있으면 확실히 드러나도록 크게 잡는다. */
    private static final int 대형조직_멤버수 = 300;
    private static final String 대형조직 = "PLANT";

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        useCase = new IncrementalSyncUseCase(
                state, writer, checker, new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        Set<MemberRef> 멤버 = new LinkedHashSet<>();
        IntStream.range(0, 대형조직_멤버수).forEach(i -> {
            DirectoryUser 동료 = 직원("u" + i, true);
            state.users.put(동료.id(), 동료);
            멤버.add(MemberRef.user(동료.id()));
        });
        state.groups.put(대형조직, new DirectoryGroup(대형조직, "ou=plant", "제1공장", 멤버));

        // FakeTupleChecker 는 allowed 에 넣은 것만 "있다" 고 답한다 — 심지 않으면
        // 삭제 델타가 아예 생기지 않는다. 300명은 실제로 활성 상태라 이 튜플들은
        // OpenFGA 에 진짜로 존재하는 것이므로, 심어 두는 것이 현실을 반영한다.
        멤버.forEach(ref -> checker.allowed.add(
                RelationTuple.directMember(ref.id(), 대형조직)));

        state.findGroupCalls.clear();
        state.findGroupHeaderCalls.clear();
        state.findUserCalls.clear();
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("직원 한 명을 비활성화할 때 그 한 명만 읽는다 — 동료 299명을 읽지 않는다")
    void 퇴사에_동료를_읽지_않는다() {
        // when — u0 퇴사
        useCase.upsertUser(직원("u0", false)).block(Duration.ofSeconds(10));

        // then — 읽은 직원은 본인뿐이다
        assertThat(state.findUserCalls)
                .as("동료를 읽어도 그 결과는 mentioning(user:u0) 이 전부 버린다")
                .containsExactly("u0");

        // then — 조직은 헤더로만 읽는다
        assertThat(state.findGroupCalls)
                .as("멤버 목록이 필요 없으므로 파티션을 통째로 읽지 않는다")
                .isEmpty();
        assertThat(state.findGroupHeaderCalls).containsExactly(대형조직);
    }

    @Test
    @DisplayName("좁혀도 델타는 그대로다 — 비활성화하면 그 직원의 튜플만 지워진다")
    void 좁혀도_델타가_같다() {
        // when
        useCase.upsertUser(직원("u0", false)).block(Duration.ofSeconds(10));

        // then — 동료의 튜플은 건드리지 않는다
        assertThat(writer.deleted)
                .as("u0 것만 지워야 한다")
                .isNotEmpty()
                .allSatisfy(tuple -> assertThat(tuple.user()).isEqualTo("user:u0"));
        assertThat(writer.written).isEmpty();
    }

    @Test
    @DisplayName("직원을 삭제할 때도 동료를 읽지 않는다 — 그 직원 하나만 읽는다")
    void 삭제에_동료를_읽지_않는다() {
        // when
        useCase.removeUser("u0").block(Duration.ofSeconds(10));

        // then
        assertThat(state.findUserCalls)
                .as("삭제 대상 본인만 읽는다")
                .containsExactly("u0");
    }

    @Test
    @DisplayName("직원을 삭제해도 동료 299명의 멤버십은 그대로 남는다 — saveGroup 에 좁힌 목록이 새면 전부 지워진다")
    void 삭제가_동료의_멤버십을_지우지_않는다() {
        // when
        useCase.removeUser("u0").block(Duration.ofSeconds(10));

        // then
        DirectoryGroup 저장된조직 = state.groups.get(대형조직);
        assertThat(저장된조직.members())
                .as("u0 만 빠지고 나머지는 그대로여야 한다")
                .hasSize(대형조직_멤버수 - 1)
                .doesNotContain(MemberRef.user("u0"))
                .contains(MemberRef.user("u1"), MemberRef.user("u299"));
    }

    /**
     * 위 테스트는 삭제 튜플이 전부 성공하는 경로만 지킨다 —
     * {@code reconcileRemovedMember} 가 실패 시 되돌아가는 원본({@code groups}, 즉
     * {@code affectedGroupsOf} 가 돌려준 전체 멤버 조직)은 그 경로에서 전혀 쓰이지 않는다.
     * 원본을 좁혀서 넘겨도 이 테스트는 여전히 통과한다 — 아무것도 지키지 못한다.
     *
     * <p>그래서 삭제 튜플 하나를 실패시켜 fallback 을 강제로 타게 만든다. 원본이 좁혀져
     * 있었다면 이 실패 경로에서 조직 멤버가 u0 하나로 줄어버린다 — 튜플 삭제가 실패했을
     * 뿐인데 멤버 299명이 함께 사라지는, 원래 위험보다 더 조용한 데이터 손실이다.
     */
    @Test
    @DisplayName("삭제 튜플이 실패해도 조직 멤버십은 그대로다 — fallback 원본이 좁혀지면 실패 하나로 동료가 전부 사라진다")
    void 삭제_튜플_실패시_동료의_멤버십이_사라지지_않는다() {
        // given — u0 의 PLANT 삭제 튜플만 실패하게 만든다
        writer.failFor(tuple -> tuple.equals(RelationTuple.directMember("u0", 대형조직)));

        // when
        useCase.removeUser("u0").block(Duration.ofSeconds(10));

        // then — 삭제가 실패했으니 조직 멤버 300명이 그대로 남아야 한다
        DirectoryGroup 저장된조직 = state.groups.get(대형조직);
        assertThat(저장된조직.members())
                .as("삭제 튜플이 실패했으므로 조직 멤버를 손대지 않아야 한다")
                .hasSize(대형조직_멤버수)
                .contains(MemberRef.user("u0"), MemberRef.user("u1"), MemberRef.user("u299"));
    }
}

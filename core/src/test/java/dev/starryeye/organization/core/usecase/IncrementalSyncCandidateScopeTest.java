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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후보 집합은 <b>이번 연산의 초점 엔티티를 언급하는 튜플</b>로 좁힌다 (설계 §5.2).
 *
 * <p>설계는 이것을 명시적으로 요구한다 — <i>"upsertUser 가 소속 조직의 전체 멤버를 확인하지
 * 않는 것이 중요하다 — 5000명 조직에 속해 있어도 후보는 kim 의 소속 수뿐이다"</i>. 최소
 * 스냅샷은 영향 조직을 멤버 목록째로 싣기 때문에, 좁히지 않으면 요청 하나가 조직 크기만큼
 * BatchCheck 를 내면서 전역 락을 쥐고 있게 된다.
 *
 * <p>여기서 못박는 두 가지: <b>(1)</b> 무관한 조직 동료의 튜플을 확인하지 않는다,
 * <b>(2)</b> 그렇게 좁혀도 네 연산의 델타는 그대로다.
 */
class IncrementalSyncCandidateScopeTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private IncrementalSyncUseCase useCase;

    private static final RelationTuple KIM_DEV001 = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple PARK_DEV001 = RelationTuple.directMember("park", "DEV001");
    private static final RelationTuple DEV001_HQ = RelationTuple.child("DEV001", "HQ");
    private static final RelationTuple LEE_HQ = RelationTuple.directMember("lee", "HQ");

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        useCase = new IncrementalSyncUseCase(
                state, writer, checker, new FakeMutationLock(), Duration.ZERO,
                IncrementalSyncUseCase.DriftObserver.NOOP);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    /**
     * DEV001 은 kim·park 를 담고 HQ 의 하위 조직이다. HQ 에는 이 연산들과 아무 상관 없는
     * lee 가 직속으로 들어 있다 — 좁히지 않으면 lee 의 튜플까지 매번 확인 대상이 된다.
     */
    private void 조직도를_심는다() {
        state.users.put("kim", 직원("kim", true));
        state.users.put("park", 직원("park", true));
        state.users.put("lee", 직원("lee", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("park"))));
        state.groups.put("HQ", new DirectoryGroup("HQ", "cn=HQ", "본사",
                Set.of(MemberRef.group("DEV001"), MemberRef.user("lee"))));

        checker.allowed.add(KIM_DEV001);
        checker.allowed.add(PARK_DEV001);
        checker.allowed.add(DEV001_HQ);
        checker.allowed.add(LEE_HQ);
    }

    @Test
    @DisplayName("upsertUser 는 소속 조직의 다른 멤버 튜플을 확인하지 않는다")
    void upsertUser_는_동료를_확인하지_않는다() {
        // given
        조직도를_심는다();

        // when — kim 을 비활성으로 바꾼다
        useCase.upsertUser(직원("kim", false)).block();

        // then — 후보는 kim 의 소속 수뿐이다. 5000명 조직이라면 이 차이가 5000배다.
        assertThat(checker.checked)
                .as("무관한 동료까지 확인하면 비용만 늘고 삭제 범위만 위험해진다(설계 §5.2)")
                .containsExactly(KIM_DEV001);

        // 좁혔어도 델타는 그대로다
        assertThat(writer.deleted).containsExactly(KIM_DEV001);
        assertThat(writer.written).isEmpty();
    }

    @Test
    @DisplayName("removeUser 도 그 직원의 튜플만 확인하고 델타는 그대로다")
    void removeUser_는_그_직원만_확인한다() {
        // given
        조직도를_심는다();

        // when
        useCase.removeUser("park").block();

        // then
        assertThat(checker.checked).containsExactly(PARK_DEV001);
        assertThat(writer.deleted).containsExactly(PARK_DEV001);
        assertThat(writer.written).isEmpty();
    }

    @Test
    @DisplayName("upsertGroup 은 그 조직의 멤버 튜플과 상위 조직으로의 child 만 확인한다")
    void upsertGroup_은_상위조직_동료를_확인하지_않는다() {
        // given
        조직도를_심는다();

        // when — DEV001 에서 kim 을 뺀다
        useCase.upsertGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("park")))).block();

        // then — child(DEV001,HQ) 는 DEV001 이 user 자리라서 양쪽을 다 봐야 잡힌다(설계 §5.2 표).
        // HQ 의 직속 멤버 lee 는 이 연산과 무관하다.
        assertThat(checker.checked)
                .containsExactlyInAnyOrder(KIM_DEV001, PARK_DEV001, DEV001_HQ);
        assertThat(checker.checked)
                .as("상위 조직을 멤버 목록째로 싣기 때문에 좁히지 않으면 여기 lee 가 섞인다")
                .doesNotContain(LEE_HQ);

        assertThat(writer.deleted).containsExactly(KIM_DEV001);
        assertThat(writer.written).isEmpty();
    }

    @Test
    @DisplayName("removeGroup 은 그 조직의 멤버 튜플과 상위 조직으로의 child 를 모두 지운다")
    void removeGroup_은_초점_튜플만_지운다() {
        // given
        조직도를_심는다();

        // when
        useCase.removeGroup("DEV001").block();

        // then
        assertThat(checker.checked)
                .containsExactlyInAnyOrder(KIM_DEV001, PARK_DEV001, DEV001_HQ);
        assertThat(writer.deleted)
                .as("좁히면서 지워야 할 것을 놓치면 조직만 사라지고 권한이 남는다")
                .containsExactlyInAnyOrder(KIM_DEV001, PARK_DEV001, DEV001_HQ);
        assertThat(writer.written).isEmpty();
    }

    @Test
    @DisplayName("upsertGroup 이 새 하위 조직을 받으면 그 child 엣지를 쓴다")
    void upsertGroup_은_새_child_엣지를_쓴다() {
        // given — 좁히기가 child 엣지를 걸러내 버리면 조직 계층이 영원히 안 만들어진다
        조직도를_심는다();
        state.groups.put("DEV002", new DirectoryGroup("DEV002", "cn=DEV002", "플랫폼팀", Set.of()));

        // when — DEV001 이 DEV002 를 하위 조직으로 받는다
        useCase.upsertGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("park"), MemberRef.group("DEV002")))).block();

        // then
        assertThat(writer.written).containsExactly(RelationTuple.child("DEV002", "DEV001"));
        assertThat(writer.deleted).isEmpty();
        assertThat(state.groups.get("DEV001").members()).contains(MemberRef.group("DEV002"));
    }
}

package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncrementalSyncUseCaseTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private MutationGate gate;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        gate = new MutationGate();
        useCase = new IncrementalSyncUseCase(state, writer, gate);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, "백엔드팀", Set.of(members));
    }

    @Test
    @DisplayName("조직에 멤버를 추가하면 그 멤버의 direct_member 튜플만 생성된다")
    void 멤버_추가가_튜플을_만든다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002")).block();

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
    }

    @Test
    @DisplayName("조직에서 멤버를 빼면 그 튜플만 삭제된다")
    void 멤버_제거가_튜플을_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"))).block();

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.directMember("lee", "DEV002"));
    }

    @Test
    @DisplayName("성공하면 변경된 조직이 현재상태에 저장된다")
    void 성공하면_상태가_저장된다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002")).block();

        // when
        useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("하위 조직을 멤버로 추가하면 child 튜플이 생성된다")
    void 하위조직_추가가_child_튜플을_만든다() {
        // given
        state.saveGroup(조직("DEV002")).block();
        state.saveGroup(조직("DEV001")).block();

        // when
        useCase.upsertGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // then
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.child("DEV002", "DEV001"));
    }

    @Test
    @DisplayName("멤버가 있는 하위 조직을 추가해도 그 하위 조직 자신의 멤버 튜플은 함께 딸려오지 않는다")
    void 하위조직_추가시_그_하위조직_자신의_멤버_튜플은_함께_딸려오지_않는다() {
        // given — DEV002 는 이미 kim 을 멤버로 갖고 있다 (기존 튜플, 이 연산과 무관)
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001")).block();

        // when — DEV001 에 하위 조직 DEV002 를 추가한다
        var result = useCase.upsertGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // then — child 튜플만 새로 생기고, DEV002 자신의 kim 소속 튜플(이미 존재)은 델타에 나타나지 않는다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.child("DEV002", "DEV001"));
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
    }

    @Test
    @DisplayName("직원을 비활성화하면 그 직원이 속한 모든 조직의 튜플이 사라진다")
    void 비활성화가_모든_소속_튜플을_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("OPS001", MemberRef.user("kim"))).block();

        // when
        var result = useCase.upsertUser(직원("kim", false)).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("kim", "OPS001"));
        assertThat(writer.appliedDeltas.get(0).toWrite()).isEmpty();
    }

    @Test
    @DisplayName("비활성 직원을 다시 활성화하면 소속 튜플이 되살아난다")
    void 재활성화가_튜플을_되살린다() {
        // given
        state.saveUser(직원("kim", false)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        useCase.upsertUser(직원("kim", true)).block();

        // then
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("조직이 이미 참조 중인 멤버의 유저 레코드가 나중에 도착해도 소속 튜플이 생성된다")
    void 조직이_먼저_참조한_유저가_나중에_도착해도_튜플이_생성된다() {
        // given — DEV002 가 이미 kim 을 멤버로 갖고 있지만, kim 의 유저 레코드는 아직 도착하지 않았다
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when — kim 의 유저 레코드가 뒤늦게 도착한다
        var result = useCase.upsertUser(직원("kim", true)).block();

        // then — 저장된 적 없는 유저를 요청값으로 되돌리면(active=true) before/after 가 같아져
        // 델타가 비어버린다. 아직 없던 유저는 비활성처럼 취급해야 한다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("상위 조직이 이미 참조 중인 하위 조직이 나중에 생겨도 child 튜플이 생성된다")
    void 상위조직이_먼저_참조한_하위조직이_나중에_도착해도_child_튜플이_생성된다() {
        // given — DEV001 이 아직 존재하지 않는 DEV002 를 하위 조직으로 갖고 있다.
        // (그 시점에는 TupleMapper 가 경고만 남기고 child 엣지를 건너뛰었다)
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when — DEV002 가 뒤늦게 도착한다
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then — 자기 멤버 튜플뿐 아니라 상위 조직에서의 child 엣지도 함께 만들어져야
        // kim 이 DEV001 로 롤업된다. 최소 스냅샷이 상위 조직을 못 보면 이 엣지가 영영 안 생긴다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toWrite()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001"));
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
    }

    @Test
    @DisplayName("이미 존재하던 하위 조직을 수정할 때는 상위 조직의 child 튜플을 다시 쓰지 않는다")
    void 기존_하위조직_수정은_상위_child_튜플을_건드리지_않는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002")).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when — DEV002 자신의 멤버만 바뀐다
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then — child 엣지는 before/after 양쪽에 있으므로 델타에 나타나지 않는다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
    }

    @Test
    @DisplayName("새 조직의 튜플 반영이 실패하면 조직 레코드를 만들지 않아 재시도가 다시 시도한다")
    void 새_조직_반영이_실패하면_레코드를_만들지_않는다() {
        // given — DEV001 이 아직 없는 DEV002 를 참조 중이고, 튜플 반영이 전부 실패한다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        writer.failFor(tuple -> true);

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then — 레코드를 만들어 버리면 다음 diff 의 "이전"에 child 엣지가 이미 포함돼
        // 그 엣지를 영원히 다시 쓰지 못한다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.groups).doesNotContainKey("DEV002");

        // when — 재시도 (실패 조건 해제)
        writer.appliedDeltas.clear();
        writer.failFor(tuple -> false);
        var retry = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(retry.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toWrite()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001"));
        assertThat(state.groups).containsKey("DEV002");
    }

    @Test
    @DisplayName("순환을 닫는 child 엣지는 최소 스냅샷이 그래프를 못 봐도 기록하지 않는다")
    void 순환을_닫는_child_엣지는_기록하지_않는다() {
        // given — A -> B -> C 사슬. 최소 스냅샷에는 C 와 그 상위 B 까지만 실리므로
        // TupleMapper 의 DFS 만으로는 A 가 C 의 조상이라는 사실을 볼 수 없다(설계 §5.3)
        state.saveGroup(조직("C")).block();
        state.saveGroup(조직("B", MemberRef.group("C"))).block();
        state.saveGroup(조직("A", MemberRef.group("B"))).block();

        // when — C 에 A 를 하위 조직으로 넣어 A -> B -> C -> A 순환을 닫으려 한다
        var result = useCase.upsertGroup(조직("C", MemberRef.group("A"))).block();

        // then — 전체 스냅샷이었다면 TupleMapper 가 거절했을 엣지다. SCIM 도 거절해야 한다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("자기 자신을 하위 조직으로 넣는 엣지도 기록하지 않는다")
    void 자기_자신을_하위조직으로_넣는_엣지는_기록하지_않는다() {
        // given
        state.saveGroup(조직("DEV002")).block();

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.group("DEV002"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("순환이 아닌 깊은 계층의 child 엣지는 정상적으로 기록된다")
    void 순환이_아닌_깊은_계층의_엣지는_기록된다() {
        // given — A -> B 사슬이 이미 있고, C 는 아직 아무 데도 안 붙어 있다
        state.saveGroup(조직("C")).block();
        state.saveGroup(조직("B")).block();
        state.saveGroup(조직("A", MemberRef.group("B"))).block();

        // when — B 아래에 C 를 붙인다. 순환이 아니므로 그대로 기록돼야 한다
        var result = useCase.upsertGroup(조직("B", MemberRef.group("C"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.child("C", "B"));
    }

    @Test
    @DisplayName("순환 검사가 조직 상한을 넘기면 조용히 추측하지 않고 요청을 실패시킨다")
    void 순환_검사는_상한을_넘기면_실패한다() {
        // given — 상한(10,000)보다 긴 사슬을 만든다. G0 -> G1 -> ... -> G10100
        int depth = 10_100;
        for (int i = depth; i >= 1; i--) {
            state.saveGroup(조직("G" + i, MemberRef.group("G" + (i + 1)))).block();
        }
        state.saveGroup(조직("G" + (depth + 1))).block();
        state.saveGroup(조직("TOP")).block();

        // when — TOP 아래에 사슬의 머리를 붙이면 순환 검사가 사슬 전체를 훑어야 한다
        var 실행 = useCase.upsertGroup(조직("TOP", MemberRef.group("G1")));

        // then — 못 본 채로 엣지를 쓰지 않고 에러로 끝난다
        assertThatThrownBy(실행::block)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환 검사");
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("같은 조직을 여러 엣지가 훑어도 저장소는 한 번만 읽는다")
    void 순환_검사는_같은_조직을_다시_읽지_않는다() {
        // given — SHARED 하나를 두 신규 하위 조직이 각각 가리킨다
        state.saveGroup(조직("SHARED", MemberRef.group("LEAF"))).block();
        state.saveGroup(조직("LEAF")).block();
        state.saveGroup(조직("X", MemberRef.group("SHARED"))).block();
        state.saveGroup(조직("Y", MemberRef.group("SHARED"))).block();
        state.findGroupCalls.clear();

        // when — 새 엣지 두 개(X, Y)가 각각 SHARED -> LEAF 를 훑는다
        var result = useCase.upsertGroup(조직("TOP", MemberRef.group("X"), MemberRef.group("Y"))).block();

        // then — 캐시가 없으면 SHARED/LEAF 를 엣지마다 한 번씩 두 번 읽는다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findGroupCalls).filteredOn("SHARED"::equals).hasSize(1);
        assertThat(state.findGroupCalls).filteredOn("LEAF"::equals).hasSize(1);
    }

    @Test
    @DisplayName("직원을 삭제하면 소속 튜플이 지워지고 현재상태에서도 사라진다")
    void 직원_삭제가_튜플과_상태를_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        var result = useCase.removeUser("kim").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
        assertThat(state.users).doesNotContainKey("kim");
        assertThat(state.groups.get("DEV002").members()).isEmpty();
    }

    @Test
    @DisplayName("조직을 삭제하면 그 조직의 튜플과 상위 조직에서의 child 튜플이 모두 지워진다")
    void 조직_삭제가_상위_child_튜플도_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when
        var result = useCase.removeGroup("DEV002").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001"));
        assertThat(state.groups).doesNotContainKey("DEV002");
        assertThat(state.groups.get("DEV001").members()).isEmpty();
    }

    @Test
    @DisplayName("변경이 없으면 OpenFGA 를 호출하지 않는다")
    void 변경이_없으면_아무것도_쓰지_않는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("일부 튜플 적용에 실패하면 반영된 만큼만 상태에 저장하고 실패를 알린다")
    void 부분_실패시_반영분만_저장한다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(조직("DEV002")).block();
        writer.failFor(tuple -> tuple.user().equals("user:lee"));

        // when
        var result = useCase.upsertGroup(
                조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"))).block();

        // then — 실패를 알리되, 반영된 kim 은 상태에도 남아야 OpenFGA 와 어긋나지 않는다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(result.hasFailure()).isTrue();
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("존재하지 않는 직원을 삭제해도 예외 없이 끝난다")
    void 없는_직원_삭제는_조용히_끝난다() {
        // given, when
        var result = useCase.removeUser("ghost").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("하위 조직을 제거해도 그 하위 조직 자신의 소속 튜플은 건드리지 않는다")
    void 하위조직_제거가_그_하위조직의_멤버_튜플을_건드리지_않는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when — DEV001 에서 하위 조직 참조를 뗀다. DEV002 자신의 멤버는 그대로다
        var result = useCase.upsertGroup(조직("DEV001")).block();

        // then — child 튜플만 지워지고, DEV002 의 kim 소속 튜플은 건드리지 않는다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.child("DEV002", "DEV001"));
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("조직을 삭제해도 그 하위 조직 자신의 소속 튜플은 건드리지 않는다")
    void 조직_삭제가_하위조직의_멤버_튜플을_건드리지_않는다() {
        // given
        state.saveUser(직원("park", true)).block();
        state.saveGroup(조직("DEV003", MemberRef.user("park"))).block();
        state.saveGroup(조직("DEV002", MemberRef.group("DEV003"))).block();

        // when
        var result = useCase.removeGroup("DEV002").block();

        // then — DEV002 -> DEV003 child 튜플만 지워지고, DEV003 자신의 park 소속 튜플은 그대로다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.child("DEV003", "DEV002"));
        assertThat(state.groups.get("DEV003").members()).containsExactly(MemberRef.user("park"));
        assertThat(state.groups).doesNotContainKey("DEV002");
    }

    @Test
    @DisplayName("직원 삭제 중 일부 조직에서 튜플 삭제가 실패하면 반영된 만큼만 상태를 지우고, 재시도가 남은 튜플을 다시 지운다")
    void 직원_삭제_부분_실패시_재시도가_남은_튜플을_다시_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("OPS001", MemberRef.user("kim"))).block();
        writer.failFor(tuple -> tuple.object().equals("group:OPS001"));

        // when
        var result = useCase.removeUser("kim").block();

        // then — 실패했으니 kim 은 삭제하지 않는다. DEV002 는 반영됐고, OPS001 은 실패해 그대로 남는다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.users).containsKey("kim");
        assertThat(state.groups.get("DEV002").members()).isEmpty();
        assertThat(state.groups.get("OPS001").members()).containsExactly(MemberRef.user("kim"));

        // when — 재시도 (실패 조건 해제 전, appliedDeltas 만 확인)
        writer.appliedDeltas.clear();
        var retry = useCase.removeUser("kim").block();

        // then — 이번엔 OPS001 튜플만 다시 지우려 시도한다 (DEV002 는 이미 끝났으니 재등장하지 않는다)
        assertThat(retry.fullyApplied()).isFalse();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.directMember("kim", "OPS001"));
    }

    @Test
    @DisplayName("조직 삭제 중 상위 조직 튜플 삭제가 실패하면 조직을 지우지 않고 재시도를 위해 남겨둔다")
    void 조직_삭제_부분_실패시_조직을_지우지_않는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        writer.failFor(tuple -> tuple.relation().equals(RelationTuple.CHILD));

        // when
        var result = useCase.removeGroup("DEV002").block();

        // then — kim 튜플은 성공적으로 지워졌지만, child 튜플 삭제는 실패해 DEV002 는 지우지 않는다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.groups).containsKey("DEV002");
        assertThat(state.groups.get("DEV002").members()).isEmpty();
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.group("DEV002"));
    }

    @Test
    @DisplayName("비활성화 반영이 전부 실패하면 활성 상태를 되돌려 재시도가 다시 삭제를 시도하게 한다")
    void 비활성화_전체_실패시_재시도가_다시_삭제를_시도한다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        writer.failFor(tuple -> true);

        // when
        var result = useCase.upsertUser(직원("kim", false)).block();

        // then — 실패했으니 active 를 되돌려 저장한다. 그대로 false 를 저장하면 다음 diff 가 이미
        // 같다고 판단해 영원히 재시도하지 못한다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.users.get("kim").active()).isTrue();

        // when — 재시도 (실패 조건 해제)
        writer.appliedDeltas.clear();
        writer.failFor(tuple -> false);
        var retry = useCase.upsertUser(직원("kim", false)).block();

        // then
        assertThat(retry.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("재적재가 도는 동안에는 네 변경 경로가 모두 거절된다")
    void 재적재_중에는_변경이_거절된다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        gate.acquire();

        // when, then — 핸들러가 아니라 유스케이스에서 막으므로 네 경로가 빠짐없이 덮인다
        assertThatThrownBy(() -> useCase.upsertUser(직원("kim", false)).block())
                .isInstanceOf(MutationsSuspendedException.class);
        assertThatThrownBy(() -> useCase.upsertGroup(조직("DEV002")).block())
                .isInstanceOf(MutationsSuspendedException.class);
        assertThatThrownBy(() -> useCase.removeUser("kim").block())
                .isInstanceOf(MutationsSuspendedException.class);
        assertThatThrownBy(() -> useCase.removeGroup("DEV002").block())
                .isInstanceOf(MutationsSuspendedException.class);

        // 거절된 요청은 아무것도 건드리지 않는다
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.users).containsKey("kim");
    }

    @Test
    @DisplayName("게이트가 열리면 변경이 다시 처리된다")
    void 게이트가_열리면_다시_처리된다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        gate.acquire();
        gate.release();

        // when
        var result = useCase.upsertUser(직원("kim", false)).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).hasSize(1);
    }

    @Test
    @DisplayName("게이트 확인은 구독 시점에 일어난다")
    void 게이트_확인은_구독_시점이다() {
        // given — Mono 를 만든 뒤에 재적재가 시작되는 경우다
        state.saveUser(직원("kim", true)).block();
        var 대기중 = useCase.upsertUser(직원("kim", false));
        gate.acquire();

        // when, then — 조립 시점의 상태를 붙들고 있으면 재적재와 경합한다
        assertThatThrownBy(대기중::block)
                .isInstanceOf(MutationsSuspendedException.class);
    }
}

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

class IncrementalSyncUseCaseTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        useCase = new IncrementalSyncUseCase(state, writer);
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
}

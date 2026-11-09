package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.UniqueKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 생성 후보 id 를 락 안에서 보고 정한 id 를 돌려준다(설계 2026-11-05 I3·I4·I5).
 */
class IncrementalSyncCreateIdTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        useCase = new IncrementalSyncUseCase(state, writer, new FakeTupleChecker(), new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    private static DirectoryUser 직원(String id, String externalId, String userName) {
        return new DirectoryUser(id, externalId, userName, userName, null, true);
    }

    private static DirectoryGroup 조직(String id, String externalId, String displayName) {
        return new DirectoryGroup(id, externalId, displayName, Set.of());
    }

    private static void 무작위_id_다(String id) {
        assertThat(id).isNotEqualTo("c-1");
        assertThat(UUID.fromString(id).version()).isEqualTo(4);
    }

    @Test
    @DisplayName("후보가 비어 있으면 그 id 로 만든다")
    void 후보가_비어_있으면_그_id_로_만든다() {
        // when
        CreateResult created = useCase.createUser(직원("c-1", "ext-1", "kim")).block();

        // then
        assertThat(created.id()).isEqualTo("c-1");
        assertThat(created.result().fullyApplied()).isTrue();
        assertThat(state.users).containsOnlyKeys("c-1");
    }

    @Test
    @DisplayName("후보에 같은 externalId 의 직원이 있으면 409 이고 아무것도 쓰지 않는다")
    void 후보에_같은_externalId_의_직원이_있으면_409_이고_아무것도_쓰지_않는다() {
        // given
        useCase.createUser(직원("c-1", "ext-1", "kim")).block();
        int 전 = writer.appliedDeltas.size();

        // when
        var 다시 = useCase.createUser(직원("c-1", "ext-1", "kim2"));

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class)
                .hasMessage("같은 externalId 의 직원이 이미 있다: c-1");
        assertThat(state.users).containsOnlyKeys("c-1");
        assertThat(state.users.get("c-1").userName()).isEqualTo("kim");
        assertThat(state.uniqueMarkers.getOrDefault(UniqueKind.USER_NAME, Map.of())).doesNotContainKey("kim2");
        assertThat(writer.appliedDeltas).hasSize(전);
    }

    @Test
    @DisplayName("후보에 externalId 가 다른 직원이 있으면 무작위 id 로 만들고 기존 직원은 그대로다")
    void 후보에_externalId_가_다른_직원이_있으면_무작위_id_로_만들고_기존_직원은_그대로다() {
        // given
        useCase.createUser(직원("c-1", "ext-OLD", "kim")).block();

        // when
        CreateResult created = useCase.createUser(직원("c-1", "ext-NEW", "park")).block();

        // then
        무작위_id_다(created.id());
        assertThat(state.users).containsOnlyKeys("c-1", created.id());
        assertThat(state.users.get("c-1").userName()).isEqualTo("kim");
    }

    @Test
    @DisplayName("externalId 대소문자만 다르면 같은 리소스가 아니다")
    void externalId_대소문자만_다르면_같은_리소스가_아니다() {
        // given
        useCase.createUser(직원("c-1", "Ext-1", "kim")).block();

        // when
        CreateResult created = useCase.createUser(직원("c-1", "ext-1", "park")).block();

        // then
        무작위_id_다(created.id());
        assertThat(state.users).containsOnlyKeys("c-1", created.id());
    }

    @Test
    @DisplayName("externalId 없는 후보가 차 있으면 무작위 id 로 만든다")
    void externalId_없는_후보가_차_있으면_무작위_id_로_만든다() {
        // given
        useCase.createUser(직원("c-1", null, "kim")).block();

        // when
        CreateResult created = useCase.createUser(직원("c-1", null, "park")).block();

        // then
        무작위_id_다(created.id());
        assertThat(state.users).containsOnlyKeys("c-1", created.id());
    }

    @Test
    @DisplayName("조직 후보에 같은 externalId 의 조직이 있으면 409 다")
    void 조직_후보에_같은_externalId_의_조직이_있으면_409다() {
        // given
        useCase.createGroup(조직("g-1", "DEV001", "개발본부")).block();

        // when
        var 다시 = useCase.createGroup(조직("g-1", "DEV001", "덮어쓴 조직"));

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class)
                .hasMessage("같은 externalId 의 조직이 이미 있다: g-1");
        assertThat(state.groups.get("g-1").displayName()).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("조직 후보에 externalId 가 다른 조직이 있으면 무작위 id 로 만들고 파티션을 읽지 않는다")
    void 조직_후보에_externalId_가_다른_조직이_있으면_무작위_id_로_만들고_파티션을_읽지_않는다() {
        // given
        useCase.createGroup(조직("g-1", "DEV001", "개발본부")).block();
        state.findGroupCalls.clear();

        // when
        CreateResult created = useCase.createGroup(조직("g-1", "DEV999", "새 조직")).block();

        // then
        assertThat(created.id()).isNotEqualTo("g-1");
        assertThat(UUID.fromString(created.id()).version()).isEqualTo(4);
        assertThat(state.groups).containsOnlyKeys("g-1", created.id());
        assertThat(state.groups.get("g-1").displayName()).isEqualTo("개발본부");
        assertThat(state.findGroupHeaderCalls).contains("g-1");
        assertThat(state.findGroupCalls).doesNotContain("g-1");
    }
}

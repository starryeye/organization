package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서버가 발급하는 id 아래의 중복 판정(설계 2026-10-04 §3.2, 점검 M7).
 */
class IncrementalSyncCreateUniquenessTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock,
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    private static DirectoryUser 직원(String id, String userName) {
        return new DirectoryUser(id, null, userName, userName, null, true);
    }

    @Test
    @DisplayName("같은 userName 의 두 번째 생성은 409 다 — 응답을 잃은 POST 를 IdP 가 다시 보내도 직원이 둘 생기지 않는다")
    void 같은_userName_두_번째_생성은_409다() {
        // given
        useCase.createUser(직원("u-1", "kim@corp.com")).block();

        // when
        var 다시 = useCase.createUser(직원("u-2", "KIM@corp.com"));

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("userName");
        assertThat(state.users).containsOnlyKeys("u-1");
    }

    @Test
    @DisplayName("같은 id·같은 userName 으로 다시 만들어도 409 다 — 생성에는 자기 자신이 없어 기존 직원을 덮어쓰지 않는다")
    void 같은_id_같은_userName_재생성은_409다() {
        // given
        useCase.createUser(직원("u-1", "kim@corp.com")).block();

        // when
        var 다시 = useCase.createUser(new DirectoryUser("u-1", null, "kim@corp.com", "덮어쓴 이름", null, true));

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("userName");
        assertThat(state.users.get("u-1").displayName()).isEqualTo("kim@corp.com");
    }

    @Test
    @DisplayName("지운 직원과 같은 userName 으로 다시 만들 수 있다 — 지운 직원의 userName 은 중복이 아니다")
    void 지운_직원과_같은_userName_으로_다시_만들_수_있다() {
        // given
        useCase.createUser(직원("u-1", "kim@corp.com")).block();
        useCase.removeUser("u-1").block();

        // when
        var result = useCase.createUser(직원("u-2", "kim@corp.com")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).containsOnlyKeys("u-2");
    }

    @Test
    @DisplayName("이름을 바꾼 사람의 옛 userName 으로 새 입사자를 만들 수 있다")
    void 옛_userName_을_새_입사자가_쓴다() {
        // given
        useCase.createUser(직원("u-1", "old@corp.com")).block();
        useCase.changeUser("u-1", user -> user.withUserName("new@corp.com")).block();

        // when
        var result = useCase.createUser(직원("u-2", "old@corp.com")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).containsOnlyKeys("u-1", "u-2");
    }

    @Test
    @DisplayName("같은 externalId 의 두 번째 조직 생성은 409 다 — externalId 가 없으면 판정하지 않는다")
    void 조직_externalId_중복은_409다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();

        // when
        var 다시 = useCase.createGroup(new DirectoryGroup("g-2", "DEV001", "개발본부", Set.of()));
        var 없음1 = useCase.createGroup(new DirectoryGroup("g-3", null, "이름만", Set.of())).block();
        var 없음2 = useCase.createGroup(new DirectoryGroup("g-4", null, "이름만", Set.of())).block();

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("externalId");
        assertThat(없음1.fullyApplied()).isTrue();
        assertThat(없음2.fullyApplied()).isTrue();
        assertThat(state.groups).containsOnlyKeys("g-1", "g-3", "g-4");
    }

    @Test
    @DisplayName("같은 id·같은 externalId 로 다시 만들어도 409 다 — 생성에는 자기 자신이 없어 기존 조직을 덮어쓰지 않는다")
    void 같은_id_같은_externalId_재생성은_409다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();

        // when
        var 다시 = useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "덮어쓴 조직", Set.of()));

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("externalId");
        assertThat(state.groups.get("g-1").displayName()).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("PUT·PATCH 로 조직 externalId 를 다른 조직의 값으로 바꾸면 409 이고 아무것도 쓰지 않는다")
    void externalId_를_남의_값으로_바꾸면_409다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();
        useCase.createGroup(new DirectoryGroup("g-2", "DEV002", "백엔드팀", Set.of())).block();

        // when
        var 바꾸기 = useCase.changeGroup("g-2", GroupChange.replacement("DEV001", "백엔드팀(개명)", Set.of()));

        // then
        assertThatThrownBy(바꾸기::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("externalId");
        assertThat(state.groups.get("g-2").externalId()).isEqualTo("DEV002");
        assertThat(state.groups.get("g-2").displayName()).isEqualTo("백엔드팀");
    }

    @Test
    @DisplayName("PUT 으로 조직 externalId 를 바꾸지 않거나 아직 아무도 안 쓰는 값으로 바꾸면 통과한다")
    void externalId_를_그대로_두거나_새_값으로_바꾸면_통과한다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();

        // when
        useCase.changeGroup("g-1", GroupChange.replacement("DEV001", "개발본부(개명)", Set.of())).block();
        useCase.changeGroup("g-1", GroupChange.replacement("DEV009", "개발본부(개명)", Set.of())).block();

        // then
        assertThat(state.groups.get("g-1").externalId()).isEqualTo("DEV009");
        assertThat(state.groups.get("g-1").displayName()).isEqualTo("개발본부(개명)");
    }

    @Test
    @DisplayName("조직 PATCH(증분)로 externalId 를 다른 조직의 값으로 바꾸면 409 이고 아무것도 쓰지 않는다(설계 2026-10-06 §4.1)")
    void PATCH_로_externalId_를_남의_값으로_바꾸면_409다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();
        useCase.createGroup(new DirectoryGroup("g-2", "DEV002", "백엔드팀", Set.of())).block();

        // when
        var 바꾸기 = useCase.changeGroup("g-2", GroupChange.delta().renamed("백엔드팀(개명)").reidentified("DEV001"));

        // then
        assertThatThrownBy(바꾸기::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("externalId");
        assertThat(state.groups.get("g-2").externalId()).isEqualTo("DEV002");
        assertThat(state.groups.get("g-2").displayName()).isEqualTo("백엔드팀");
    }

    @Test
    @DisplayName("조직 PATCH(증분)로 externalId 만 바꾸면 이름은 그대로다")
    void PATCH_로_externalId_만_바꾼다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();

        // when
        useCase.changeGroup("g-1", GroupChange.delta().reidentified("DEV009")).block();

        // then
        assertThat(state.groups.get("g-1").externalId()).isEqualTo("DEV009");
        assertThat(state.groups.get("g-1").displayName()).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("조직 PATCH 로 externalId 를 비우면, 다른 조직이 그 옛 값으로 만들어질 수 있다")
    void externalId_를_비우면_옛_값을_다른_조직이_쓴다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();
        useCase.changeGroup("g-1", GroupChange.delta().reidentified(null)).block();

        // when
        useCase.createGroup(new DirectoryGroup("g-2", "DEV001", "새 개발본부", Set.of())).block();

        // then
        assertThat(state.groups.get("g-1").externalId()).isNull();
        assertThat(state.groups.get("g-2").externalId()).isEqualTo("DEV001");
    }
}

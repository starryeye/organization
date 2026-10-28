package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditRecorder;
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
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static dev.starryeye.organization.core.usecase.RevocationObserver.Kind.DEACTIVATE;
import static dev.starryeye.organization.core.usecase.RevocationObserver.Kind.DELETE_GROUP;
import static dev.starryeye.organization.core.usecase.RevocationObserver.Kind.DELETE_USER;
import static dev.starryeye.organization.core.usecase.RevocationObserver.Kind.REMOVE_MEMBER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커밋된 회수를 종류별로 센다(설계 2026-10-27 §8). 상태 쓰기가 성공한 자리에서만 세고, 직원 하나의 회수는 한 번만 센다 — 삭제가 일부 실패해 비활성으로
 * 남기면 {@code deactivate} 이고, 그 뒤 재시도가 레코드를 지울 때는 이미 비활성이라 세지 않는다. 그룹 쪽은 실패하면 의도를 저장하지 않으므로 커밋된
 * 만큼만 센다 — 장애 중 IdP 재시도가 거짓 경보를 울리지 않는다.
 *
 * <p>준비는 {@link IncrementalSyncRevocationIntentTest} 와 같다 — OpenFGA 는 가짜 장부 하나이고 Check 가 {@link FakeTupleWriter#stored} 에 묻는다.
 */
class IncrementalSyncRevocationCountTest {

    private static final MemberRef 김 = MemberRef.user("kim");
    private static final MemberRef 이 = MemberRef.user("lee");
    private static final MemberRef 하위 = MemberRef.group("SUB001");
    private static final RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple 김_운영 = RelationTuple.directMember("kim", "OPS001");
    private static final RelationTuple 이_개발 = RelationTuple.directMember("lee", "DEV001");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    /** 관찰자가 받은 수를 종류별로 더한다. 부르지 않은 종류는 없다. */
    private Map<RevocationObserver.Kind, Integer> 센_것;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        // Check 는 가짜 장부에 묻는다. failFor 는 그대로 따른다(super.check 가 먼저 던진다)
        checker = new FakeTupleChecker() {
            @Override
            public Mono<Boolean> check(RelationTuple tuple) {
                return super.check(tuple).map(ignored -> writer.stored.contains(tuple));
            }
        };
        lock = new FakeMutationLock();
        센_것 = Collections.synchronizedMap(new EnumMap<>(RevocationObserver.Kind.class));
    }

    private IncrementalSyncUseCase 유스케이스() {
        return 유스케이스((kind, count) -> 센_것.merge(kind, count, Integer::sum));
    }

    private IncrementalSyncUseCase 유스케이스(RevocationObserver 관찰자) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, Duration.ofSeconds(10),
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP, AuditRecorder.NONE, context -> "trace-1",
                IncrementalSyncUseCase.DEFAULT_LOCK_TTL, 관찰자);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", active);
    }

    /** 가짜 장부를 지금 상태대로 채운다. */
    private void 장부를_상태대로_채운다() {
        writer.stored.addAll(TupleMapper.toTuples(state.loadAll().block()).tuples());
    }

    /** 활성인 kim 이 DEV001·OPS001 에 속하고, OpenFGA 장부가 상태와 같다. */
    private void 두_조직에_속한_활성_김을_심는다() {
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(김))).block();
        state.saveGroup(new DirectoryGroup("OPS001", "OPS001", "운영본부", Set.of(김))).block();
        장부를_상태대로_채운다();
    }

    /** DEV001 에 활성인 kim·lee 와 하위 조직 SUB001 이 있고, OpenFGA 장부가 상태와 같다. */
    private void 직원_둘과_하위_조직_하나를_둔_개발본부를_심는다() {
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(new DirectoryGroup("SUB001", "SUB001", "백엔드팀", Set.of())).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(김, 이, 하위))).block();
        장부를_상태대로_채운다();
    }

    @Test
    @DisplayName("활성 직원의 비활성화가 저장되면 deactivate 를 1 센다")
    void 비활성화가_저장되면_deactivate_1() {
        // given
        두_조직에_속한_활성_김을_심는다();

        // when
        var result = 유스케이스().changeUser("kim", user -> user.withActive(false)).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(센_것).isEqualTo(Map.of(DEACTIVATE, 1));
    }

    @Test
    @DisplayName("이미 비활성인 직원을 고쳐도 세지 않는다 — 회수가 아니다")
    void 이미_비활성인_직원의_수정은_세지_않는다() {
        // given — 비활성 kim 이 DEV001 의 멤버다(비활성이라 장부에 줄이 없다)
        state.saveUser(직원("kim", false)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(김))).block();
        IncrementalSyncUseCase useCase = 유스케이스();

        // when — 비활성을 다시 보내고, 이름을 바꾼다
        useCase.changeUser("kim", user -> user.withActive(false)).block();
        useCase.changeUser("kim", user -> user.withDisplayName("김철수")).block();

        // then
        assertThat(state.users.get("kim").displayName()).isEqualTo("김철수");
        assertThat(센_것).isEmpty();
    }

    @Test
    @DisplayName("삭제가 일부 실패해 비활성으로 남기면 deactivate 로 세고, 재시도가 레코드를 지울 때는 이미 비활성이라 세지 않는다")
    void 삭제가_일부_실패해_비활성으로_남기면_deactivate_이고_재시도로_지워질_때는_세지_않는다() {
        // given — OPS001 의 줄만 지우지 못한다
        두_조직에_속한_활성_김을_심는다();
        writer.failFor(김_운영::equals);
        IncrementalSyncUseCase useCase = 유스케이스();

        // when
        var 실패한_삭제 = useCase.removeUser("kim").block();

        // then — 비활성으로 남겼다
        assertThat(실패한_삭제.fullyApplied()).isFalse();
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(센_것).isEqualTo(Map.of(DEACTIVATE, 1));

        // when — 재시도가 남은 줄과 레코드를 지운다
        writer.failFor(tuple -> false);
        var 재시도 = useCase.removeUser("kim").block();

        // then — 직원 하나의 회수는 한 번만 센다
        assertThat(재시도.fullyApplied()).isTrue();
        assertThat(state.users).doesNotContainKey("kim");
        assertThat(센_것).isEqualTo(Map.of(DEACTIVATE, 1));
    }

    @Test
    @DisplayName("활성 직원을 지우면 delete_user 를 1 센다 — 함께 빠지는 소속은 멤버 빼기로 세지 않는다")
    void 활성_직원을_지우면_delete_user_1() {
        // given
        두_조직에_속한_활성_김을_심는다();

        // when
        var result = 유스케이스().removeUser("kim").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).doesNotContainKey("kim");
        assertThat(센_것).isEqualTo(Map.of(DELETE_USER, 1));
    }

    @Test
    @DisplayName("PATCH 로 멤버를 빼면 뺀 직원 수만큼 remove_member 를 센다 — 하위 조직은 세지 않는다")
    void PATCH_로_뺀_직원_수만큼_remove_member() {
        // given
        직원_둘과_하위_조직_하나를_둔_개발본부를_심는다();

        // when
        var result = 유스케이스().changeGroup("DEV001",
                GroupChange.delta().removingId("kim").removingId("lee").removingId("SUB001")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.groups.get("DEV001").members()).isEmpty();
        assertThat(센_것).isEqualTo(Map.of(REMOVE_MEMBER, 2));
    }

    @Test
    @DisplayName("PUT 의 새 목록에서 빠진 직원도 remove_member 로 센다")
    void PUT_으로_빠진_직원도_remove_member() {
        // given
        직원_둘과_하위_조직_하나를_둔_개발본부를_심는다();

        // when — lee 와 하위 조직만 남긴다
        var result = 유스케이스().changeGroup("DEV001",
                GroupChange.replacement("DEV001", "개발본부", Set.of(이, 하위))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.groups.get("DEV001").members()).containsExactlyInAnyOrder(이, 하위);
        assertThat(센_것).isEqualTo(Map.of(REMOVE_MEMBER, 1));
    }

    @Test
    @DisplayName("멤버 목록을 통째로 교체하는 입구(upsertGroup)로 빠진 직원도 remove_member 로 센다")
    void 통째_교체로_빠진_직원도_remove_member() {
        // given
        직원_둘과_하위_조직_하나를_둔_개발본부를_심는다();

        // when — 하위 조직만 남긴다
        var result = 유스케이스().upsertGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(하위))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.groups.get("DEV001").members()).containsExactly(하위);
        assertThat(센_것).isEqualTo(Map.of(REMOVE_MEMBER, 2));
    }

    @Test
    @DisplayName("조직을 지우면 직원 멤버 수만큼 delete_group 을 센다 — 하위 조직은 세지 않는다")
    void 조직을_지우면_직원_멤버_수만큼_delete_group() {
        // given
        직원_둘과_하위_조직_하나를_둔_개발본부를_심는다();

        // when
        var result = 유스케이스().removeGroup("DEV001").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.groups).doesNotContainKey("DEV001");
        assertThat(센_것).isEqualTo(Map.of(DELETE_GROUP, 2));
    }

    @Test
    @DisplayName("조직 삭제가 일부 실패하면 지운 직원만 delete_group 으로 세고, 재시도가 나머지를 센다")
    void 조직_삭제가_일부_실패하면_지운_직원만_세고_재시도가_나머지를_센다() {
        // given — kim 의 줄만 지우지 못한다
        직원_둘과_하위_조직_하나를_둔_개발본부를_심는다();
        writer.failFor(김_개발::equals);
        IncrementalSyncUseCase useCase = 유스케이스();

        // when
        var 실패한_삭제 = useCase.removeGroup("DEV001").block();

        // then — 조직은 남고 lee 만 빠졌다
        assertThat(실패한_삭제.fullyApplied()).isFalse();
        assertThat(state.groups.get("DEV001").members()).containsExactly(김);
        assertThat(센_것).isEqualTo(Map.of(DELETE_GROUP, 1));

        // when — 재시도
        writer.failFor(tuple -> false);
        var 재시도 = useCase.removeGroup("DEV001").block();

        // then — 합이 직원 멤버 수다
        assertThat(재시도.fullyApplied()).isTrue();
        assertThat(state.groups).doesNotContainKey("DEV001");
        assertThat(센_것).isEqualTo(Map.of(DELETE_GROUP, 2));
    }

    @Test
    @DisplayName("그룹 쪽이 일부 실패하면 커밋된 만큼만 세고, 재시도가 나머지를 센다 — 겹쳐 세지 않는다")
    void 그룹_쪽이_실패하면_커밋된_만큼만_세고_재시도가_나머지를_센다() {
        // given — DEV001 에서 kim·lee 를 빼는데 lee 의 줄은 지우지 못한다
        직원_둘과_하위_조직_하나를_둔_개발본부를_심는다();
        writer.failFor(이_개발::equals);
        IncrementalSyncUseCase useCase = 유스케이스();
        GroupChange 둘을_뺀다 = GroupChange.delta().removingId("kim").removingId("lee");

        // when
        var 실패한_빼기 = useCase.changeGroup("DEV001", 둘을_뺀다).block();

        // then — kim 만 커밋됐다
        assertThat(실패한_빼기.fullyApplied()).isFalse();
        assertThat(state.groups.get("DEV001").members()).containsExactlyInAnyOrder(이, 하위);
        assertThat(센_것).isEqualTo(Map.of(REMOVE_MEMBER, 1));

        // when — IdP 가 같은 PATCH 를 다시 보낸다
        writer.failFor(tuple -> false);
        var 재시도 = useCase.changeGroup("DEV001", 둘을_뺀다).block();

        // then — 합이 뺀 직원 수다
        assertThat(재시도.fullyApplied()).isTrue();
        assertThat(state.groups.get("DEV001").members()).containsExactly(하위);
        assertThat(센_것).isEqualTo(Map.of(REMOVE_MEMBER, 2));
    }

    @Test
    @DisplayName("관찰자가 던져도 쓰기는 그대로 끝난다 — 지표는 막지 않고 보이게만 한다(설계 2026-10-27 §17.4)")
    void 관찰자가_던져도_쓰기는_그대로_끝난다() {
        // given
        두_조직에_속한_활성_김을_심는다();
        IncrementalSyncUseCase useCase = 유스케이스((kind, count) -> {
            throw new IllegalStateException("관찰자 실패(테스트)");
        });

        // when
        var result = useCase.changeUser("kim", user -> user.withActive(false)).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(writer.stored).isEmpty();
    }
}

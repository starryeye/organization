package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCIM 쓰기가 키에 들어갈 값의 한도를 표지판·OpenFGA 보다 먼저 본다(설계 2026-10-17 §3). 넘으면 {@link ValueTooLongException} 이고
 * 그 요청은 아무것도 쓰지 않는다 — 실제 DynamoDB 는 키가 한도를 넘으면 OpenFGA 를 쓴 뒤에 거절해 둘이 어긋났다.
 */
class IncrementalSyncKeyLimitTest {

    private static final MemberRef 김 = MemberRef.user("kim");

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

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", true);
    }

    /** OpenFGA 가 지금 상태와 어긋남 없이 같다고 둔다 — 한도를 안 보면 이어지는 쓰기가 델타로 나타나게 한다. */
    private void openFga를_상태와_맞춘다() {
        checker.allowed.addAll(TupleMapper.toTuples(state.loadAll().block()).tuples());
    }

    /** 요청 전의 저장 상태 — 요청이 아무것도 쓰지 않았는지 맞대는 기준이다. */
    private record 저장상태(Map<String, DirectoryUser> 직원들, Map<String, DirectoryGroup> 조직들) {
    }

    private 저장상태 지금_상태() {
        return new 저장상태(new LinkedHashMap<>(state.users), new LinkedHashMap<>(state.groups));
    }

    /** 한도를 넘어 400 이 되고, 저장소·OpenFGA·표지판 어디에도 쓰지 않았다. */
    private void 넘어서_아무것도_쓰지_않았다(Supplier<?> 요청, String 속성, 저장상태 전) {
        assertThatThrownBy(요청::get)
                .isInstanceOfSatisfying(ValueTooLongException.class, error -> {
                    assertThat(error.attribute()).isEqualTo(속성);
                    // 문구는 속성과 한도만 싣고 값은 싣지 않는다
                    assertThat(error.getMessage())
                            .isEqualTo("%s 이(가) %d바이트를 넘습니다".formatted(error.attribute(), error.limitBytes()));
                });
        assertThat(state.users).isEqualTo(전.직원들());
        assertThat(state.groups).isEqualTo(전.조직들());
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.uniqueMarkers.values()).allMatch(Map::isEmpty);
    }

    @Test
    @DisplayName("표시명이 1024바이트를 넘는 직원의 생성은 아무것도 쓰지 않는다")
    void 긴_표시명의_직원_생성은_아무것도_쓰지_않는다() {
        // given
        저장상태 전 = 지금_상태();
        DirectoryUser 긴 = 직원("kim").withDisplayName("x".repeat(1025));

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.createUser(긴).block(), "displayName", 전);
    }

    @Test
    @DisplayName("바꾼 결과가 한도를 넘는 직원의 변경은 아무것도 쓰지 않는다 — 새 userName 의 표지판도, 비활성화의 튜플도 쓰지 않는다")
    void 바꾼_결과가_넘는_직원_변경은_아무것도_쓰지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        state.saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of(김))).block();
        openFga를_상태와_맞춘다();
        저장상태 전 = 지금_상태();

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.changeUser("kim",
                user -> user.withUserName("kim2").withActive(false).withExternalId("x".repeat(1025))).block(),
                "externalId", 전);
    }

    @Test
    @DisplayName("표시명이 1024바이트를 넘는 조직의 생성은 OpenFGA 를 쓰지 않는다")
    void 긴_표시명의_조직_생성은_OpenFGA_를_쓰지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        저장상태 전 = 지금_상태();
        DirectoryGroup 긴 = new DirectoryGroup("DEV", "ext-DEV", "x".repeat(1025), Set.of(김));

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.createGroup(긴).block(), "displayName", 전);
    }

    @Test
    @DisplayName("공백뿐이고 1024바이트를 넘는 externalId 의 조직은 표지판도 차지하지 않는다")
    void 공백뿐인_긴_externalId_의_조직은_표지판도_차지하지_않는다() {
        // given
        저장상태 전 = 지금_상태();
        DirectoryGroup 공백 = new DirectoryGroup("DEV", " ".repeat(1025), "개발", Set.of());

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.createGroup(공백).block(), "externalId", 전);
    }

    @Test
    @DisplayName("아이디가 1000바이트를 넘는 멤버를 넣는 조직 변경은 아무것도 쓰지 않는다")
    void 긴_멤버를_넣는_조직_변경은_아무것도_쓰지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        state.saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of(김))).block();
        openFga를_상태와_맞춘다();
        저장상태 전 = 지금_상태();
        GroupChange 변경 = GroupChange.delta().reidentified("ext-NEW")
                .adding(Set.of(MemberRef.user("u".repeat(1001))));

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.changeGroup("DEV", 변경).block(), "members", 전);
    }

    @Test
    @DisplayName("아이디가 1000바이트를 넘는 멤버로 조직을 통째로 교체해도 아무것도 쓰지 않는다")
    void 긴_멤버로_교체하는_조직_변경은_아무것도_쓰지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        state.saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of(김))).block();
        openFga를_상태와_맞춘다();
        저장상태 전 = 지금_상태();
        GroupChange 교체 = GroupChange.replacement("ext-NEW", "개발", Set.of(MemberRef.group("g".repeat(1001))));

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.changeGroup("DEV", 교체).block(), "members", 전);
    }

    @Test
    @DisplayName("빼는 멤버는 보지 않는다 — 긴 아이디를 빼는 변경은 오류 없이 끝나고 멤버가 그대로다")
    void 빼는_멤버는_보지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        state.saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of(김))).block();
        openFga를_상태와_맞춘다();

        // when
        useCase.changeGroup("DEV", GroupChange.delta().removingId("u".repeat(1001))).block();

        // then
        assertThat(state.groups.get("DEV").members()).containsExactly(김);
    }

    @Test
    @DisplayName("한도 바이트의 값은 그대로 쓴다 — 표시명이 정확히 1024바이트인 직원이 저장된다")
    void 한도_바이트의_값은_그대로_쓴다() {
        // given
        String 한도 = "x".repeat(1024);

        // when
        useCase.createUser(직원("kim").withDisplayName(한도)).block();

        // then
        assertThat(state.users.get("kim").displayName()).isEqualTo(한도);
    }

    @Test
    @DisplayName("테스트용 upsertUser 도 한도를 넘는 직원을 쓰지 않는다")
    void upsertUser_도_넘으면_아무것도_쓰지_않는다() {
        // given
        저장상태 전 = 지금_상태();
        DirectoryUser 긴 = 직원("kim").withUserName("X".repeat(1025));

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.upsertUser(긴).block(), "userName", 전);
    }

    @Test
    @DisplayName("테스트용 upsertGroup 도 한도를 넘는 멤버의 조직을 쓰지 않는다")
    void upsertGroup_도_넘으면_아무것도_쓰지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        저장상태 전 = 지금_상태();
        DirectoryGroup 긴 = new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of(김, MemberRef.user("u".repeat(1001))));

        // when, then
        넘어서_아무것도_쓰지_않았다(() -> useCase.upsertGroup(긴).block(), "members", 전);
    }
}

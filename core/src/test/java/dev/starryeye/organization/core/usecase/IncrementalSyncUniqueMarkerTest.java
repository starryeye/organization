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
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCIM 입구가 유일 값 표지판을 차지하고 정리한다(설계 2026-10-14 §4.2~§4.5). */
class IncrementalSyncUniqueMarkerTest {

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

    private Map<String, UniqueClaim> 적은것(UniqueKind 종류) {
        return state.uniqueMarkers.getOrDefault(종류, Map.of());
    }

    private void 적는다(UniqueKind 종류, String 키, String 주인, Instant 시각) {
        state.uniqueMarkers.computeIfAbsent(종류, ignored -> new LinkedHashMap<>()).put(키, new UniqueClaim(주인, 시각));
    }

    /** DEV001 이 아직 없는 직원 u-1 을 멤버로 적어 두었다 — u-1 을 만들면 튜플이 생긴다. */
    private void u1이_든_조직을_심는다() {
        state.groups.put("DEV001", new DirectoryGroup("DEV001", null, "개발", Set.of(MemberRef.user("u-1"))));
    }

    // ---------- 직원 ----------

    @Test
    @DisplayName("생성은 userName 의 소문자 키로 표지판을 차지한다")
    void 생성은_표지판을_차지한다() {
        // when
        useCase.createUser(직원("u-1", "Kim.Lee")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("kim.lee");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim.lee").ownerId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("응답을 잃은 POST 를 IdP 가 다시 보내면(새 UUID) 409 이고 직원은 하나다")
    void 다시_보낸_POST_는_409다() {
        // given
        useCase.createUser(직원("u-1", "kim")).block();

        // when, then
        assertThatThrownBy(() -> useCase.createUser(직원("u-2", "kim")).block())
                .isInstanceOf(DirectoryConflictException.class);
        assertThat(state.users).containsOnlyKeys("u-1");
    }

    @Test
    @DisplayName("다른 쓰기가 차지하고 아직 저장하지 않은 새 표지판이면 503 이고 아무것도 쓰지 않는다")
    void 새_표지판의_주인이_아직_저장하지_않았으면_503이다() {
        // given — 지금(실제 시계) 차지한 표지판, 주인 META 는 아직 없다
        적는다(UniqueKind.USER_NAME, "kim", "in-flight", Instant.now());

        // when, then
        assertThatThrownBy(() -> useCase.createUser(직원("u-2", "kim")).block())
                .isInstanceOf(TemporaryFailureException.class);
        assertThat(state.users).isEmpty();
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("in-flight");
    }

    @Test
    @DisplayName("생성이 일부 실패로 커밋을 건너뛰면 표지판을 놓아, 재시도가 곧바로 통과한다")
    void 생성이_일부_실패하면_표지판을_놓아_재시도가_곧_통과한다() {
        // given
        u1이_든_조직을_심는다();
        writer.failFor(tuple -> true);

        // when
        var 처음 = useCase.createUser(직원("u-1", "kim")).block();

        // then
        assertThat(처음.fullyApplied()).isFalse();
        assertThat(state.users).doesNotContainKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME)).doesNotContainKey("kim");

        // when — IdP 의 재시도는 새 UUID 다
        writer.failFor(tuple -> false);
        var 다시 = useCase.createUser(직원("u-2", "kim")).block();

        // then
        assertThat(다시.fullyApplied()).isTrue();
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("생성이 오류로 끝나도 표지판을 놓는다")
    void 생성이_오류로_끝나도_표지판을_놓는다() {
        // given
        u1이_든_조직을_심는다();
        writer.abortWhen(delta -> true);

        // when
        assertThatThrownBy(() -> useCase.createUser(직원("u-1", "kim")).block())
                .isInstanceOf(TupleWriteAbortedException.class);

        // then
        assertThat(state.users).doesNotContainKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME)).doesNotContainKey("kim");
    }

    @Test
    @DisplayName("이름을 바꾸면 새 이름을 차지하고 커밋 뒤 옛 이름을 놓아, 남이 곧 옛 이름을 쓴다")
    void 개명은_옛_표지판을_놓는다() {
        // given
        useCase.createUser(직원("u-1", "old")).block();

        // when
        useCase.changeUser("u-1", u -> u.withUserName("new")).block();
        useCase.createUser(직원("u-2", "old")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("new").ownerId()).isEqualTo("u-1");
        assertThat(적은것(UniqueKind.USER_NAME).get("old").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("Kim 에서 kim 처럼 대소문자만 바꾸면 표지판을 건드리지 않고 자기 자신과 부딪치지 않는다")
    void 대소문자만_바꾸면_표지판을_건드리지_않는다() {
        // given
        useCase.createUser(직원("u-1", "Kim")).block();
        UniqueClaim 전 = 적은것(UniqueKind.USER_NAME).get("kim");

        // when
        useCase.changeUser("u-1", u -> u.withUserName("kim")).block();

        // then
        assertThat(state.users.get("u-1").userName()).isEqualTo("kim");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isSameAs(전);
    }

    @Test
    @DisplayName("비활성화처럼 이름을 안 바꾸는 변경은 표지판을 건드리지 않는다")
    void 비활성화는_표지판을_건드리지_않는다() {
        // given
        useCase.createUser(직원("u-1", "kim")).block();
        UniqueClaim 전 = 적은것(UniqueKind.USER_NAME).get("kim");

        // when
        useCase.changeUser("u-1", u -> u.withActive(false)).block();

        // then
        assertThat(state.users.get("u-1").active()).isFalse();
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isSameAs(전);
    }

    @Test
    @DisplayName("개명이 오류로 끝나도 요청값을 저장하므로 META 는 새 이름이고, 옛 표지판만 놓고 새 표지판은 남긴다(설계 2026-10-20 §6.1)")
    void 개명이_오류로_끝나도_META_가_새_이름이면_옛_표지판을_놓는다() {
        // given — u-1 은 DEV001 의 멤버다. 이름을 바꾸며 비활성화하면 튜플을 지운다
        state.groups.put("DEV001", new DirectoryGroup("DEV001", null, "개발", Set.of(MemberRef.user("u-1"))));
        useCase.createUser(직원("u-1", "old")).block();
        checker.allowed.add(RelationTuple.directMember("u-1", "DEV001")); // 방금 쓴 튜플이 OpenFGA 에 있다 — 지우는 델타가 생긴다
        writer.abortWhen(delta -> true);

        // when
        assertThatThrownBy(() -> useCase.changeUser("u-1", u -> u.withUserName("new").withActive(false)).block())
                .isInstanceOf(TupleWriteAbortedException.class);

        // then — OpenFGA 단계가 오류로 끝나도 요청값을 저장한다(설계 2026-10-20 §6.1). 예전에는 META 가 옛 이름으로 남았다.
        // 정리는 그대로 주인이 쥔 이름을 본다 — 새 표지판은 남고 옛 표지판만 놓는다
        assertThat(state.users.get("u-1").userName()).isEqualTo("new");
        assertThat(state.users.get("u-1").active()).isFalse();
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("new");
    }

    @Test
    @DisplayName("개명이 일부 실패해도 META 가 새 이름이면 옛 표지판만 놓고 새 표지판은 남긴다 — 커밋이 건너뛰어지지 않는다")
    void 개명이_일부_실패해도_META_가_새_이름이면_옛_표지판을_놓는다() {
        // given — u-1 은 DEV001 의 멤버다. 이름을 바꾸며 비활성화하면 튜플을 지우는데 그 쓰기가 모두 실패한다
        u1이_든_조직을_심는다();
        useCase.createUser(직원("u-1", "old")).block();
        checker.allowed.add(RelationTuple.directMember("u-1", "DEV001")); // 방금 쓴 튜플이 OpenFGA 에 있다 — 지우는 델타가 생긴다
        writer.failFor(tuple -> true);

        // when
        var 결과 = useCase.changeUser("u-1", u -> u.withUserName("new").withActive(false)).block();

        // then — 일부 반영이지만 변경 커밋은 새 이름으로 저장했다. 새 표지판은 주인이 쥐고 있어 남고 옛 표지판만 놓는다
        assertThat(결과.fullyApplied()).isFalse();
        assertThat(state.users.get("u-1").userName()).isEqualTo("new");
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("new");
    }

    @Test
    @DisplayName("커밋 뒤 옛 표지판을 놓다가 실패해도 요청은 성공이다")
    void 놓기가_실패해도_요청은_성공이다() {
        // given
        useCase.createUser(직원("u-1", "old")).block();
        state.failReleaseUnique = new IllegalStateException("놓기 실패(테스트)");

        // when
        var result = useCase.changeUser("u-1", u -> u.withUserName("new")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users.get("u-1").userName()).isEqualTo("new");
        assertThat(적은것(UniqueKind.USER_NAME).get("old").ownerId()).as("남은 표지판은 나중에 넘겨받는다").isEqualTo("u-1");
    }

    @Test
    @DisplayName("직원을 지우면 표지판을 놓는다")
    void 삭제는_표지판을_놓는다() {
        // given
        useCase.createUser(직원("u-1", "kim")).block();

        // when
        useCase.removeUser("u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).doesNotContainKey("kim");
    }

    @Test
    @DisplayName("직원 삭제가 일부 실패해 직원이 남으면 표지판도 남긴다")
    void 삭제가_일부_실패하면_표지판을_남긴다() {
        // given
        u1이_든_조직을_심는다();
        useCase.createUser(직원("u-1", "kim")).block();
        checker.allowed.add(RelationTuple.directMember("u-1", "DEV001")); // 방금 쓴 튜플이 OpenFGA 에 있다 — 지우는 델타가 생긴다
        writer.failFor(tuple -> true);

        // when
        useCase.removeUser("u-1").block();

        // then
        assertThat(state.users).containsKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("도중에 리스를 잃어 일이 취소되면 표지판을 놓지 않는다 — 락 없이 쓰지 않는다")
    void 리스를_잃어_취소되면_표지판을_놓지_않는다() {
        // given — Check 가 느린 사이 갱신(50ms 주기)이 실패한다
        u1이_든_조직을_심는다();
        checker.delayBy(tuple -> Duration.ofMillis(300));
        lock.failRenew = true;
        IncrementalSyncUseCase 짧은_갱신 = new IncrementalSyncUseCase(state, writer, checker, lock,
                Duration.ZERO, Duration.ofMillis(50), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        // when
        assertThatThrownBy(() -> 짧은_갱신.createUser(직원("u-1", "kim")).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);

        // then
        assertThat(state.users).doesNotContainKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).as("락 TTL 뒤 넘겨받는다").isEqualTo("u-1");
    }

    @Test
    @DisplayName("커밋 직전 리스 확인이 실패해 끝나도 표지판을 놓지 않는다 — 남이 락을 쥐었을 수 있다")
    void 커밋_직전_리스_확인이_실패하면_표지판을_놓지_않는다() {
        // given — 소속 조직이 없어 튜플 변화는 없고, 커밋 직전 확인에서 리스를 잃은 것이 드러난다
        lock.failRenew = true;

        // when
        assertThatThrownBy(() -> useCase.createUser(직원("u-1", "kim")).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);

        // then
        assertThat(state.users).doesNotContainKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).as("놓으면 다른 서버의 재시도가 차지한 표지판을 지울 수 있다").isEqualTo("u-1");
    }

    // ---------- 조직 ----------

    @Test
    @DisplayName("조직 생성은 externalId 를 차지하고, 같은 externalId 의 두 번째 생성은 409 다")
    void 조직_생성은_표지판을_차지한다() {
        // when
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID).get("EXT-1").ownerId()).isEqualTo("g-1");
        assertThatThrownBy(() -> useCase.createGroup(new DirectoryGroup("g-2", "EXT-1", "개발2", Set.of())).block())
                .isInstanceOf(DirectoryConflictException.class);
    }

    @Test
    @DisplayName("조직 PATCH 로 externalId 를 비우면 커밋 뒤 옛 표지판을 놓는다 — 빈 값은 차지하지 않는다")
    void 조직_externalId_를_비우면_옛_표지판을_놓는다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // when
        useCase.changeGroup("g-1", GroupChange.delta().reidentified(null)).block();

        // then
        assertThat(state.groups.get("g-1").externalId()).isNull();
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).isEmpty();
    }

    @Test
    @DisplayName("조직 externalId 를 바꾸면 새 값을 차지하고 커밋 뒤 옛 값을 놓는다")
    void 조직_externalId_변경은_옛_표지판을_놓는다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // when
        useCase.changeGroup("g-1", GroupChange.replacement("EXT-2", "개발", Set.of())).block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).containsOnlyKeys("EXT-2");
    }

    @Test
    @DisplayName("조직 externalId 변경이 오류로 끝나면 새 표지판만 놓고 옛 표지판은 남긴다 — META 는 옛 값이다")
    void 조직_externalId_변경이_오류로_끝나면_새_표지판만_놓는다() {
        // given — g-1 은 u-1 을 멤버로 둔다. 멤버를 빼며 externalId 를 바꾸면 지우는 튜플이 생긴다
        state.users.put("u-1", 직원("u-1", "kim"));
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of(MemberRef.user("u-1")))).block();
        checker.allowed.add(RelationTuple.directMember("u-1", "g-1"));
        writer.abortWhen(delta -> true);

        // when
        assertThatThrownBy(() -> useCase.changeGroup("g-1", GroupChange.replacement("EXT-2", "개발", Set.of())).block())
                .isInstanceOf(TupleWriteAbortedException.class);

        // then
        assertThat(state.groups.get("g-1").externalId()).isEqualTo("EXT-1");
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).containsOnlyKeys("EXT-1");
    }

    @Test
    @DisplayName("조직을 지우면 표지판을 놓는다")
    void 조직_삭제는_표지판을_놓는다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // when
        useCase.removeGroup("g-1").block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).isEmpty();
    }
}

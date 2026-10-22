package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCIM 은 회수 의도를 저장한다(설계 2026-10-20 §6.1·§6.2). OpenFGA 단계가 실패·오류로 끝나도 요청한 {@code active} 를 저장하고, 직원 삭제가 실패하면
 * 비활성으로 남긴다. 락을 못 잡았거나 리스를 잃었으면, 그리고 아직 저장된 적 없는 직원이면 저장하지 않는다.
 *
 * <p>OpenFGA 는 가짜 장부 하나다 — Check 가 {@link FakeTupleWriter#stored} 에 물어, 실패한 쓰기 뒤에 남은 줄을 다음 쓰기가 그대로 본다.
 */
class IncrementalSyncRevocationIntentTest {

    private static final Instant 고정시각 = Instant.parse("2026-10-20T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple 김_운영 = RelationTuple.directMember("kim", "OPS001");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private FakeAuditLog auditLog;

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
        auditLog = new FakeAuditLog();
    }

    private IncrementalSyncUseCase 유스케이스() {
        return 유스케이스(state, Duration.ofSeconds(10));
    }

    private IncrementalSyncUseCase 유스케이스(FakeStateRepository 저장소, Duration 갱신주기) {
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC));
        return new IncrementalSyncUseCase(저장소, writer, checker, lock, Duration.ZERO, 갱신주기,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP, audit, context -> "trace-1");
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", active);
    }

    /** 활성인 kim 이 DEV001·OPS001 에 속하고, OpenFGA 장부가 상태와 같다. */
    private void 두_조직에_속한_활성_김을_심는다(FakeStateRepository 저장소) {
        저장소.saveUser(직원("kim", true)).block();
        저장소.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(김))).block();
        저장소.saveGroup(new DirectoryGroup("OPS001", "OPS001", "운영본부", Set.of(김))).block();
        writer.stored.addAll(TupleMapper.toTuples(저장소.loadAll().block()).tuples());
    }

    @Test
    @DisplayName("비활성화가 일부 실패해도 비활성으로 저장하고 부분 반영을 알린다(설계 §6.1)")
    void 비활성화가_일부_실패해도_비활성으로_저장하고_부분_반영을_알린다() {
        // given — 두 소속 중 OPS001 의 줄만 지우지 못한다
        두_조직에_속한_활성_김을_심는다(state);
        writer.failFor(김_운영::equals);

        // when
        var result = 유스케이스().changeUser("kim", user -> user.withActive(false)).block();

        // then — 응답은 지금처럼 일부 반영(503)이고, 상태는 요청한 비활성이다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(writer.stored).containsExactly(김_운영);
        assertThat(auditLog.lastOf(김).attributes()).containsExactly(new AttributeChange("active", "true", "false"));
    }

    @Test
    @DisplayName("비활성화의 Check 가 실패해도 비활성으로 저장하고 오류를 낸다(설계 §6.1)")
    void 비활성화의_Check가_실패해도_비활성으로_저장하고_오류를_낸다() {
        // given
        두_조직에_속한_활성_김을_심는다(state);
        checker.failFor(tuple -> true);

        // when, then — 오류는 그대로 나간다(핸들러가 503 으로 바꾼다)
        assertThatThrownBy(() -> 유스케이스().changeUser("kim", user -> user.withActive(false)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Check 실패(테스트)");
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(auditLog.lastOf(김).attributes()).containsExactly(new AttributeChange("active", "true", "false"));
    }

    @Test
    @DisplayName("비활성화의 쓰기가 차단기로 멈춰도 비활성으로 저장한다(설계 §6.1)")
    void 비활성화의_쓰기가_차단기로_멈춰도_비활성으로_저장한다() {
        // given
        두_조직에_속한_활성_김을_심는다(state);
        writer.failFor(김_운영::equals);
        writer.abortWhen(delta -> true);

        // when, then
        assertThatThrownBy(() -> 유스케이스().changeUser("kim", user -> user.withActive(false)).block())
                .isInstanceOf(TupleWriteAbortedException.class);
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(writer.stored).containsExactly(김_운영);
    }

    @Test
    @DisplayName("리스를 잃으면 비활성화를 저장하지 않는다 — 다른 인스턴스의 저장을 덮을 수 있다(설계 §6.1)")
    void 리스를_잃으면_비활성화를_저장하지_않는다() {
        // given — OpenFGA 에서 지우는 사이 리스를 잃는다. 커밋 직전 확인이 실패한다
        두_조직에_속한_활성_김을_심는다(state);
        writer.onApply(() -> lock.failRenew = true);

        // when, then
        assertThatThrownBy(() -> 유스케이스().changeUser("kim", user -> user.withActive(false)).block())
                .isInstanceOf(LockUnavailableException.class);
        assertThat(state.users.get("kim").active()).isTrue();
    }

    @Test
    @DisplayName("도중에 리스를 잃어 일이 취소되면 비활성화를 저장하지 않는다(설계 §6.1)")
    void 도중에_리스를_잃어_취소되면_비활성화를_저장하지_않는다() {
        // given — Check 가 300ms 걸리는 사이 하트비트 갱신이 실패한다
        두_조직에_속한_활성_김을_심는다(state);
        checker.delayBy(tuple -> Duration.ofMillis(300));
        lock.failRenew = true;

        // when, then
        assertThatThrownBy(() -> 유스케이스(state, Duration.ofMillis(50))
                .changeUser("kim", user -> user.withActive(false)).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(state.users.get("kim").active()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("Check 가 실패한 뒤 리스 확인도 실패하면 저장하지 않고 리스 오류를 내며 원래 오류를 붙인다(설계 §6.1)")
    void Check가_실패한_뒤_리스를_잃었으면_저장하지_않고_리스_오류에_원래_오류를_붙여_낸다() {
        // given — Check 보다 앞서는 리스 확인은 없다. 의도를 저장하기 직전의 확인이 처음이다
        두_조직에_속한_활성_김을_심는다(state);
        checker.failFor(tuple -> true);
        lock.failRenew = true;

        // when
        Throwable 던진_것 = catchThrowable(() -> 유스케이스().changeUser("kim", user -> user.withActive(false)).block());

        // then — 응답은 같은 503(LockUnavailableException)이고, 원래 오류는 suppressed 로 남는다. block() 이 붙이는 표시도 suppressed 에 든다
        assertThat(던진_것).isInstanceOf(LockUnavailableException.class);
        assertThat(던진_것.getSuppressed()).anySatisfy(붙은_것 -> assertThat(붙은_것)
                .isInstanceOf(IllegalStateException.class).hasMessage("Check 실패(테스트)"));
        assertThat(lock.renewAttempted).as("저장 전에 리스를 확인했다").hasValue(1);
        assertThat(state.users.get("kim").active()).isTrue();
    }

    @Test
    @DisplayName("비활성화가 실패한 뒤 active 를 싣지 않은 다음 쓰기가 남은 줄을 지운다(설계 §6.1)")
    void 비활성화가_실패한_뒤_다음_쓰기가_남은_줄을_지운다() {
        // given — 비활성화의 지우기가 전부 실패했다. 상태는 비활성, 장부에는 두 줄이 남았다
        두_조직에_속한_활성_김을_심는다(state);
        writer.failFor(tuple -> true);
        IncrementalSyncUseCase useCase = 유스케이스();
        useCase.changeUser("kim", user -> user.withActive(false)).block();
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(writer.stored).containsExactlyInAnyOrder(김_개발, 김_운영);
        writer.failFor(tuple -> false);

        // when — IdP 는 비활성을 다시 보내지 않고 이름만 바꾼다
        var result = useCase.changeUser("kim", user -> user.withDisplayName("김철수")).block();

        // then — 저장된 비활성과 Check 기준선의 차이로 남은 줄을 지운다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.stored).isEmpty();
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(state.users.get("kim").displayName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("재활성화가 실패해도 활성으로 저장하고, 빠진 줄은 이 직원을 건드리는 다음 쓰기가 쓴다(설계 §6.1)")
    void 활성화가_실패해도_활성으로_저장하고_빠진_줄은_다음_쓰기가_쓴다() {
        // given — 비활성 kim 이 DEV001 의 멤버다(비활성이라 장부에 줄이 없다). 재활성화의 쓰기가 실패한다
        state.saveUser(직원("kim", false)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(김))).block();
        writer.failFor(김_개발::equals);
        IncrementalSyncUseCase useCase = 유스케이스();

        // when
        var 실패한_재활성화 = useCase.changeUser("kim", user -> user.withActive(true)).block();

        // then — 요청한 활성을 저장했는데 줄은 빠져 있다. GET-diff IdP 는 이 상태를 다시 보내지 않는다
        assertThat(실패한_재활성화.fullyApplied()).isFalse();
        assertThat(state.users.get("kim").active()).isTrue();
        assertThat(writer.stored).isEmpty();
        writer.failFor(tuple -> false);

        // when — active 를 싣지 않고 이름만 바꾸는 다음 쓰기
        var 다음_쓰기 = useCase.changeUser("kim", user -> user.withDisplayName("김철수")).block();

        // then — 저장된 활성과 Check 기준선의 차이로 빠진 줄을 쓴다
        assertThat(다음_쓰기.fullyApplied()).isTrue();
        assertThat(writer.stored).contains(김_개발);
        assertThat(state.users.get("kim").active()).isTrue();
        assertThat(state.users.get("kim").displayName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("직원 삭제가 일부 실패하면 비활성 레코드로 남고, 재시도한 DELETE 가 끝낸다(설계 §6.2)")
    void 직원_삭제가_일부_실패하면_비활성_레코드로_남고_재시도가_끝낸다() {
        // given — OPS001 의 줄만 지우지 못한다
        두_조직에_속한_활성_김을_심는다(state);
        writer.failFor(김_운영::equals);
        IncrementalSyncUseCase useCase = 유스케이스();

        // when
        var result = useCase.removeUser("kim").block();

        // then — 레코드와 지우지 못한 소속은 남고, 직원은 비활성이다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(state.groups.get("DEV001").members()).isEmpty();
        assertThat(state.groups.get("OPS001").members()).containsExactly(김);
        assertThat(writer.stored).containsExactly(김_운영);
        assertThat(auditLog.lastOf(김).attributes()).containsExactly(new AttributeChange("active", "true", "false"));

        // when — 재시도
        writer.failFor(tuple -> false);
        var retry = useCase.removeUser("kim").block();

        // then — 남은 줄을 Check 기준선으로 지우고 레코드를 지운다
        assertThat(retry.fullyApplied()).isTrue();
        assertThat(writer.stored).isEmpty();
        assertThat(state.users).doesNotContainKey("kim");
        assertThat(state.groups.get("OPS001").members()).isEmpty();
    }

    @Test
    @DisplayName("직원 삭제가 오류로 끝나도 비활성 레코드로 남는다(설계 §6.2)")
    void 직원_삭제가_오류로_끝나도_비활성_레코드로_남는다() {
        // given
        두_조직에_속한_활성_김을_심는다(state);
        writer.abortWhen(delta -> true);

        // when, then
        assertThatThrownBy(() -> 유스케이스().removeUser("kim").block())
                .isInstanceOf(TupleWriteAbortedException.class);
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(state.groups.get("DEV001").members()).as("커밋하지 않았으니 소속은 그대로다 — 재시도가 Check 로 정리한다")
                .containsExactly(김);
    }

    @Test
    @DisplayName("처음 만드는 직원이 OpenFGA 단계에서 오류로 끝나면 레코드를 만들지 않는다 — 재시도 POST 가 409 로 막히지 않는다(설계 §6.1)")
    void 처음_만드는_직원이_OpenFGA_단계에서_오류로_끝나면_레코드를_만들지_않는다() {
        // given — DEV001 이 아직 없는 kim 을 멤버로 적어 두었다. kim 의 줄을 쓰다가 차단기가 선다
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(김))).block();
        writer.abortWhen(delta -> true);

        // when, then
        assertThatThrownBy(() -> 유스케이스().createUser(직원("kim", true)).block())
                .isInstanceOf(TupleWriteAbortedException.class);
        assertThat(state.users).doesNotContainKey("kim");

        // given — Check 가 실패해도 같다
        writer.abortWhen(delta -> false);
        checker.failFor(tuple -> true);

        // when, then
        assertThatThrownBy(() -> 유스케이스().createUser(직원("kim", true)).block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.users).doesNotContainKey("kim");
    }

    @Test
    @DisplayName("의도를 저장하다 실패하면 원래 오류(Check 실패)가 나간다(설계 §6.1)")
    void 의도를_저장하다_실패하면_원래_오류가_나간다() {
        // given — Check 가 실패하고, 그 뒤 의도를 저장하는 쓰기도 실패한다
        직원_저장이_실패하는_저장소 저장이_실패하는_저장소 = new 직원_저장이_실패하는_저장소();
        두_조직에_속한_활성_김을_심는다(저장이_실패하는_저장소);
        checker.failFor(tuple -> true);

        // when, then — 저장의 오류가 아니라 Check 의 오류가 나간다
        assertThatThrownBy(() -> 유스케이스(저장이_실패하는_저장소, Duration.ofSeconds(10))
                .changeUser("kim", user -> user.withActive(false)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Check 실패(테스트)");
        assertThat(저장이_실패하는_저장소.저장_시도).as("의도를 저장하려 했다").hasValue(1);
        assertThat(저장이_실패하는_저장소.users.get("kim").active()).isTrue();
    }

    @Test
    @DisplayName("남은 회수 지우기는 저장된 비활성 직원의 남은 줄만 락 안에서 Check 기준선으로 지우고, 상태는 바꾸지 않는다(설계 §6.4)")
    void 남은_회수_지우기는_저장된_비활성_직원의_남은_줄만_지운다() {
        // given — 비활성으로 저장된 kim 의 줄이 두 소속 모두에 남았다. 동료 lee 의 줄은 상태와 맞다
        두_조직에_속한_활성_김을_심는다(state);
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(김, MemberRef.user("lee")))).block();
        writer.stored.add(RelationTuple.directMember("lee", "DEV001"));
        state.saveUser(직원("kim", true), 직원("kim", false)).block();
        DirectoryUser 저장본 = state.users.get("kim");

        // when
        var result = 유스케이스().revokeLeftovers("kim").block();

        // then — 남은 줄만 지웠다. 상태는 그대로(이름·활성·소속)이고 이력에 지운 줄이 남는다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.stored).containsExactly(RelationTuple.directMember("lee", "DEV001"));
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toWrite()).isEmpty();
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactlyInAnyOrder(김_개발, 김_운영);
        assertThat(state.users.get("kim")).isEqualTo(저장본);
        assertThat(state.groups.get("DEV001").members()).contains(김);
        assertThat(auditLog.lastOf(김).revoked()).containsExactlyInAnyOrder(김_개발, 김_운영);
        assertThat(auditLog.lastOf(김).attributes()).as("직원 레코드를 다시 저장하지 않았다").isEmpty();
        assertThat(lock.acquired).as("락 안에서 했다").hasValue(1);
        assertThat(lock.released).hasValue(1);
        assertThat(checker.checked).as("기준선은 Check 다").isNotEmpty();
    }

    @Test
    @DisplayName("남은 회수 지우기는 빠진 줄을 쓰지 않는다 — 낡은 상태가 지운 직원의 권한을 되살리면 안 된다(설계 §6.4)")
    void 남은_회수_지우기는_빠진_줄을_쓰지_않는다() {
        // given — 상태는 활성 kim 이 두 소속이라 말하지만 장부에는 한 줄만 있다(직원 삭제가 OpenFGA 에서 끝나고 커밋이 실패한 모양)
        두_조직에_속한_활성_김을_심는다(state);
        writer.stored.remove(김_운영);

        // when
        var result = 유스케이스().revokeLeftovers("kim").block();

        // then — 쓰지 않았다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(writer.stored).containsExactly(김_개발);
        assertThat(state.users.get("kim").active()).isTrue();
    }

    @Test
    @DisplayName("남은 회수 지우기는 직원이 없으면 빈 결과다 — 락 안에서 읽는다(설계 §6.4)")
    void 남은_회수_지우기는_직원이_없으면_빈_결과다() {
        // given, when
        var result = 유스케이스().revokeLeftovers("nobody").block();

        // then
        assertThat(result).isNull();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("남은 회수 지우기는 쓰기 경로의 드리프트 관찰을 부르지 않는다 — 같은 줄은 아카이빙 게이지가 이미 센다(설계 §6.4)")
    void 남은_회수_지우기는_쓰기_경로의_드리프트_관찰을_부르지_않는다() {
        // given — 비활성 kim 의 줄이 남았다. 쓰기 경로의 관찰자를 기록하는 유스케이스다
        List<String> 본_것 = new ArrayList<>();
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC));
        IncrementalSyncUseCase useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO,
                Duration.ofSeconds(10), (extra, missing) -> 본_것.add(extra + "/" + missing), LockObserver.NOOP, audit,
                context -> "trace-1");
        두_조직에_속한_활성_김을_심는다(state);
        state.saveUser(직원("kim", true), 직원("kim", false)).block();

        // when
        var result = useCase.revokeLeftovers("kim").block();

        // then — 지웠지만 관찰은 부르지 않았다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.stored).isEmpty();
        assertThat(본_것).isEmpty();

        // given — 대조: 같은 어긋남을 쓰기 요청이 만나면 관찰자를 부른다 (기록기가 연결돼 있다는 증거)
        writer.stored.addAll(Set.of(김_개발, 김_운영));

        // when
        useCase.changeUser("kim", user -> user.withDisplayName("김철수")).block();

        // then
        assertThat(본_것).containsExactly("2/0");
    }

    @Test
    @DisplayName("남은 회수 지우기가 일부 실패하면 부분 반영을 알리고 상태는 그대로다(설계 §6.4)")
    void 남은_회수_지우기가_일부_실패하면_부분_반영을_알린다() {
        // given
        두_조직에_속한_활성_김을_심는다(state);
        state.saveUser(직원("kim", true), 직원("kim", false)).block();
        writer.failFor(김_운영::equals);

        // when
        var result = 유스케이스().revokeLeftovers("kim").block();

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(writer.stored).containsExactly(김_운영);
        assertThat(state.users.get("kim").active()).isFalse();
    }

    /** 시드({@code saveUser(user)})는 그대로 두고, 유스케이스가 쓰는 {@code saveUser(before, after)} 만 실패한다. */
    private static class 직원_저장이_실패하는_저장소 extends FakeStateRepository {

        final AtomicInteger 저장_시도 = new AtomicInteger();

        @Override
        public Mono<Void> saveUser(DirectoryUser before, DirectoryUser after) {
            return Mono.defer(() -> {
                저장_시도.incrementAndGet();
                return Mono.error(new UnsupportedOperationException("직원 저장 실패(테스트)"));
            });
        }
    }
}

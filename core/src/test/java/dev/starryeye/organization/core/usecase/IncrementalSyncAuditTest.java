package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCIM 쓰기의 이력(설계 2026-10-10 §4.2·§4.4·§4.5). */
class IncrementalSyncAuditTest {

    private static final Instant 고정시각 = Instant.parse("2026-10-10T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final MemberRef 개발 = MemberRef.group("DEV");
    private static final MemberRef 팀 = MemberRef.group("TEAM");

    private FakeStateRepository state;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private FakeAuditLog auditLog;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        FakeTupleWriter writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC));
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, Duration.ofSeconds(10),
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP, audit, context -> "trace-1");
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", active);
    }

    /** OpenFGA 가 지금 상태와 어긋남 없이 같다고 둔다 — 이 스위트의 관심은 이력이다. */
    private void openFga를_상태와_맞춘다() {
        checker.allowed.addAll(TupleMapper.toTuples(state.loadAll().block()).tuples());
    }

    @Test
    @DisplayName("직원을 만들면 생성과 속성을, 그 요청의 traceId 를 원인으로 남긴다")
    void 직원_생성() {
        // when
        useCase.createUser(직원("kim", true)).block();

        // then
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(new AuditCause(SyncSource.SCIM, AuditKind.SCIM_REQUEST, "trace-1")));
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.created()).isTrue();
        assertThat(김의_줄.attributes()).contains(new AttributeChange("userName", null, "kim"));
    }

    @Test
    @DisplayName("직원을 바꾸면 바뀐 속성의 이전과 이후를 남긴다")
    void 직원_변경() {
        // given
        state.saveUser(직원("kim", true)).block();

        // when
        useCase.changeUser("kim", user -> user.withDisplayName("김철수")).block();

        // then
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.created()).isFalse();
        assertThat(김의_줄.attributes()).containsExactly(new AttributeChange("displayName", "kim", "김철수"));
    }

    @Test
    @DisplayName("비활성화하면 잃은 권한을 남기고, 소속은 그대로라 나감이 없다")
    void 비활성화() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of(김))).block();
        openFga를_상태와_맞춘다();

        // when
        useCase.changeUser("kim", user -> user.withActive(false)).block();

        // then
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.attributes()).containsExactly(new AttributeChange("active", "true", "false"));
        assertThat(김의_줄.revoked()).containsExactly(RelationTuple.directMember("kim", "DEV"));
        assertThat(김의_줄.left()).isEmpty();
    }

    @Test
    @DisplayName("조직에 멤버를 넣으면 조직 줄에는 멤버 들어옴을, 직원 줄에는 소속 들어옴과 얻은 권한을 남긴다")
    void 멤버_넣기() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of())).block();
        openFga를_상태와_맞춘다();

        // when
        useCase.changeGroup("DEV", GroupChange.delta().adding(Set.of(김))).block();

        // then
        AuditEntry 개발의_줄 = auditLog.lastOf(개발);
        assertThat(개발의_줄.membersAdded()).containsExactly(김);
        assertThat(개발의_줄.attributes()).isEmpty();
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.joined()).containsExactly("DEV");
        assertThat(김의_줄.granted()).containsExactly(RelationTuple.directMember("kim", "DEV"));
    }

    @Test
    @DisplayName("직원을 지우면 삭제·나간 조직·잃은 권한을 남기고, 조직 줄에는 멤버 나감을 남긴다")
    void 직원_삭제() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of(김))).block();
        openFga를_상태와_맞춘다();

        // when
        useCase.removeUser("kim").block();

        // then
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.deleted()).isTrue();
        assertThat(김의_줄.left()).containsExactly("DEV");
        assertThat(김의_줄.revoked()).containsExactly(RelationTuple.directMember("kim", "DEV"));
        assertThat(auditLog.lastOf(개발).membersRemoved()).containsExactly(김);
    }

    @Test
    @DisplayName("조직을 지우면 그 조직의 삭제·멤버 나감·상위에서 나감과, 상위 조직의 멤버 나감을 남긴다")
    void 조직_삭제() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(new DirectoryGroup("TEAM", "TEAM", "팀", Set.of(김))).block();
        state.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of(팀))).block();
        openFga를_상태와_맞춘다();

        // when
        useCase.removeGroup("TEAM").block();

        // then
        AuditEntry 팀의_줄 = auditLog.lastOf(팀);
        assertThat(팀의_줄.deleted()).isTrue();
        assertThat(팀의_줄.membersRemoved()).containsExactly(김);
        assertThat(팀의_줄.left()).containsExactly("DEV");
        assertThat(팀의_줄.revoked()).containsExactly(RelationTuple.child("TEAM", "DEV"));
        assertThat(auditLog.lastOf(개발).membersRemoved()).containsExactly(팀);
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.left()).containsExactly("TEAM");
        assertThat(김의_줄.revoked()).containsExactly(RelationTuple.directMember("kim", "TEAM"));
    }

    @Test
    @DisplayName("이력은 락을 반납한 뒤, 응답하기 전에 쓴다")
    void 기록은_락을_반납한_뒤에_쓴다() {
        // given
        AtomicBoolean 쓸때_락을_쥠 = new AtomicBoolean(true);
        auditLog.onWrite = () -> 쓸때_락을_쥠.set(lock.isHeld());

        // when
        useCase.createUser(직원("kim", true)).block();

        // then — block 이 돌아왔을 때 이미 썼다
        assertThat(auditLog.events).hasSize(1);
        assertThat(쓸때_락을_쥠).isFalse();
    }

    @Test
    @DisplayName("이력의 시각은 반납 뒤 쓴 시각이 아니라 락 안에서 커밋한 시각이다")
    void 시각은_커밋한_시각이다() {
        // given — 반납하는 순간 시계가 1분 간다. 이력은 반납 뒤에 쓴다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(고정시각);
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, 시계가_가는_락.clock());
        IncrementalSyncUseCase 시계를_보는_유스케이스 = new IncrementalSyncUseCase(state, new FakeTupleWriter(), checker,
                시계가_가는_락, Duration.ZERO, Duration.ofSeconds(10), IncrementalSyncUseCase.DriftObserver.NOOP,
                LockObserver.NOOP, audit, context -> "trace-1");

        // when
        시계를_보는_유스케이스.createUser(직원("kim", true)).block();

        // then — 반납이 시계를 옮겼고, 이력은 그 전 시각이다
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(고정시각.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(고정시각));
    }

    @Test
    @DisplayName("바뀐 것이 없는 요청은 남기지 않는다")
    void 바뀐_것이_없으면_남기지_않는다() {
        // given
        state.saveUser(직원("kim", true)).block();

        // when
        useCase.changeUser("kim", user -> user).block();

        // then
        assertThat(auditLog.events).isEmpty();
    }

    /** 직원을 지우는 마지막 커밋이 실패하는 저장소 — 그 앞의 소속 조직 빼기는 이미 커밋돼 변경 모음에 들어간 뒤다. */
    private static class 삭제가_실패하는_저장소 extends FakeStateRepository {
        @Override
        public Mono<Void> deleteUser(String userId) {
            return Mono.error(new IllegalStateException("삭제 실패"));
        }
    }

    @Test
    @DisplayName("커밋 자리가 기록한 뒤 오류로 끝난 요청은 남기지 않는다")
    void 오류로_끝난_요청은_남기지_않는다() {
        // given — 소속 조직 빼기가 변경 모음에 들어간 뒤, 직원 삭제 커밋이 실패한다
        FakeStateRepository 실패하는_저장소 = new 삭제가_실패하는_저장소();
        실패하는_저장소.saveUser(직원("kim", true)).block();
        실패하는_저장소.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of(김))).block();
        checker.allowed.addAll(TupleMapper.toTuples(실패하는_저장소.loadAll().block()).tuples());
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC));
        IncrementalSyncUseCase 실패하는_유스케이스 = new IncrementalSyncUseCase(실패하는_저장소, new FakeTupleWriter(), checker,
                lock, Duration.ZERO, Duration.ofSeconds(10), IncrementalSyncUseCase.DriftObserver.NOOP,
                LockObserver.NOOP, audit, context -> "trace-1");

        // when, then
        assertThatThrownBy(() -> 실패하는_유스케이스.removeUser("kim").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("삭제 실패");
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("이력 쓰기가 실패해도 요청은 성공한다")
    void 이력_쓰기가_실패해도_요청은_성공한다() {
        // given
        auditLog.failWith = new IllegalStateException("DynamoDB 장애");

        // when
        IncrementalSyncResult result = useCase.createUser(직원("kim", true)).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).containsKey("kim");
    }
}

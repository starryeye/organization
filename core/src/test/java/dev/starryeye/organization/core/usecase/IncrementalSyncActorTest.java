package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.Actor;
import dev.starryeye.organization.core.audit.AuditCause;
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
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.SyncSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** SCIM 쓰기의 이력은 요청 Context 의 행위자를 원인에 남긴다 — 요청이 떠난 뒤에도(설계 2026-10-28 인증 §5.2). */
class IncrementalSyncActorTest {

    private static final Instant 고정시각 = Instant.parse("2026-10-29T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeMutationLock lock;
    private FakeAuditLog auditLog;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC));
        useCase = new IncrementalSyncUseCase(state, writer, new FakeTupleChecker(), lock, Duration.ZERO,
                Duration.ofSeconds(10), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP, audit,
                context -> "trace-1");
        state.saveUser(new DirectoryUser("kim", "emp-kim", "kim", "kim", "kim@example.com", true)).block();
    }

    private static DirectoryGroup kim이_든_DEV() {
        return new DirectoryGroup("DEV", "DEV", "개발", Set.of(김));
    }

    @Test
    @DisplayName("SCIM 쓰기의 이력에는 요청 Context 의 행위자가 원인으로 남는다")
    void SCIM_쓰기의_이력에_행위자가_남는다() {
        // when
        useCase.upsertGroup(kim이_든_DEV()).contextWrite(Context.of(Actor.KEY, Actor.client("idp"))).block();

        // then
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(new AuditCause(SyncSource.SCIM, AuditKind.SCIM_REQUEST, "trace-1", "client:idp")));
    }

    @Test
    @DisplayName("쓰는 도중 요청이 떠나도 이어서 남기는 이력 줄에 그 요청의 행위자가 있다")
    void 요청이_떠나도_행위자는_남는다() {
        // given — OpenFGA 쓰기의 응답이 늦다. 반영한 것은 알린 뒤라 이력에 이미 모였고, 떠난 요청의 쓰기는 이어서 끝난다
        writer.delay = Duration.ofMillis(300);

        // when — 쓰기가 락 안에서 도는 동안 요청이 구독을 끊는다
        StepVerifier.create(useCase.upsertGroup(kim이_든_DEV()).contextWrite(Context.of(Actor.KEY, Actor.client("idp"))))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        // then — 일이 끝난 뒤 남긴 이력 줄의 원인에 요청의 행위자가 있다. 떠나며 시스템 행위자로 바뀌지 않는다
        await().atMost(Duration.ofSeconds(5)).until(() -> !auditLog.events.isEmpty());
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(new AuditCause(SyncSource.SCIM, AuditKind.SCIM_REQUEST, "trace-1", "client:idp")));
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.released.get() == 1);
        assertThat(state.groups.get("DEV").members()).as("떠난 요청의 쓰기도 커밋까지 간다").containsExactly(김);
    }
}

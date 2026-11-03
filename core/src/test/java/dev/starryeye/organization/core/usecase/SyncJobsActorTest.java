package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.Actor;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 회차를 건 행위자는 요청 Context 에서 한 번 읽어 회차의 요청자와 이력의 원인에 함께 남긴다(설계 2026-10-28 인증 §5.2). */
class SyncJobsActorTest {

    private static final Instant 지금 = Instant.parse("2026-10-29T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV");

    private final FakeSyncRunRepository runs = new FakeSyncRunRepository(지금);
    private final FakeMutationLock lock = new FakeMutationLock();
    private final FakeAuditLog auditLog = new FakeAuditLog();
    private final AuditRecorder audit = new AuditRecorder(auditLog, 빠진_줄 -> {
    }, Clock.fixed(지금, ZoneOffset.UTC));
    private final SyncJobs jobs = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP,
            Duration.ofMinutes(1), audit);

    /** 김에게 권한 하나를 준 회차를 건다 — 이력이 한 건 남는다. */
    private Mono<SyncRun> 김에게_주는_회차() {
        return jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC,
                AuditKind.SYNC, (started, 기록) -> Mono.fromSupplier(() -> {
                    기록.changes().tuples(new TupleWriteResult(Set.of(김_개발), Set.of(), List.of()));
                    return Mono.just(SyncOutcome.noChange());
                }), run -> {
                });
    }

    @Test
    @DisplayName("요청 Context 의 행위자가 회차의 요청자와 이력의 원인에 함께 남는다")
    void 요청_Context_의_행위자가_회차와_이력에_남는다() {
        // given
        String 행위자 = Actor.client("ops");

        // when
        SyncRun started = 김에게_주는_회차().contextWrite(Context.of(Actor.KEY, 행위자)).block();
        SyncRun finished = runs.awaitFinished(started.runId());

        // then — 열린 기록·끝난 기록·이력의 원인이 모두 같은 행위자다
        assertThat(started.requestedBy()).isEqualTo("client:ops");
        assertThat(finished.requestedBy()).isEqualTo("client:ops");
        assertThat(auditLog.events).singleElement()
                .satisfies(event -> assertThat(event.cause().actor()).isEqualTo("client:ops"));
    }

    @Test
    @DisplayName("Context 에 행위자가 없으면 회차의 요청자도 이력의 원인도 system:unknown 이다")
    void 행위자가_없으면_unknown_이다() {
        // when
        SyncRun started = 김에게_주는_회차().block();
        SyncRun finished = runs.awaitFinished(started.runId());

        // then
        assertThat(started.requestedBy()).isEqualTo(Actor.UNKNOWN);
        assertThat(finished.requestedBy()).isEqualTo(Actor.UNKNOWN);
        assertThat(auditLog.events).singleElement()
                .satisfies(event -> assertThat(event.cause().actor()).isEqualTo(Actor.UNKNOWN));
    }
}

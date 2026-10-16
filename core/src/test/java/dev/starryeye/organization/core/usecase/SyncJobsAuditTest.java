package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.audit.RunAudit;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 회차의 이력을 SyncJobs 가 모으고, 회차가 어떻게 끝나든 남긴다(설계 2026-10-15 §3). */
class SyncJobsAuditTest {

    private static final Instant 지금 = Instant.parse("2026-10-15T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV");

    private final FakeSyncRunRepository runs = new FakeSyncRunRepository(지금);
    private final FakeMutationLock lock = new FakeMutationLock();
    private final FakeAuditLog auditLog = new FakeAuditLog();
    private final AtomicInteger 빠진_줄_지표 = new AtomicInteger();
    private final AuditRecorder audit =
            new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, Clock.fixed(지금, ZoneOffset.UTC));

    private SyncJobs 잡스(Duration renewInterval, Duration timeout) {
        return new SyncJobs(runs, lock, renewInterval, LockObserver.NOOP, timeout, audit);
    }

    private SyncJobs 잡스() {
        return 잡스(Duration.ofSeconds(10), Duration.ofMinutes(1));
    }

    private SyncRun 건다(SyncJobs jobs, BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>> work) {
        SyncRun started = jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC,
                AuditKind.SYNC, work, run -> {
                }).block();
        return runs.awaitFinished(started.runId());
    }

    /** 김에게 권한 하나를 준 쓰기가 끝났다. */
    private static void 김에게_줬다(RunAudit 기록) {
        기록.changes().tuples(new TupleWriteResult(Set.of(김_개발), Set.of(), List.of()));
    }

    @Test
    @DisplayName("값으로 끝난 회차는 모은 것을 회차 id 와 종류로, 락 안에서 끝난 시각에 남긴다")
    void 값으로_끝나면_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.cause()).isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, run.runId()));
            assertThat(event.at()).isEqualTo(지금);
        });
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("모은 것이 없으면 쓰지 않는다")
    void 빈_모음은_쓰지_않는다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.just(Mono.just(SyncOutcome.noChange())));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("아무것도 모으지 못하고 실패한 회차는 쓰지 않는다")
    void 아무것도_모으지_못하고_실패하면_쓰지_않는다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.error(new IllegalStateException("LDAP 읽기 실패")));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("락 안의 일이 오류로 끝나도 그때까지 모은 것을 FAILED 회차로 남긴다")
    void 오류로_끝나도_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>error(new IllegalStateException("현재상태 교체 실패"));
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("현재상태 교체 실패");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("반납 뒤 할 일이 실패해도 모은 것을 남긴다")
    void 반납_뒤_할_일이_실패해도_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.<SyncOutcome>error(new IllegalStateException("스냅샷 저장 실패"));
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("스냅샷 저장 실패");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("기한으로 멈춘 회차도 그때까지 모은 것을 남긴다")
    void 기한으로_멈춰도_남긴다() {
        // when
        SyncRun run = 건다(잡스(Duration.ofSeconds(10), Duration.ofMillis(200)), (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>never();
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).startsWith("기한 초과");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("리스를 잃어 멈춘 회차도 그때까지 모은 것을 남긴다")
    void 리스를_잃어도_남긴다() {
        // given
        lock.failRenew = true;

        // when
        SyncRun run = 건다(잡스(Duration.ofMillis(50), Duration.ofMinutes(1)), (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>never();
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("리스");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("서버가 내려가 멈춘 회차도 그때까지 모은 것을 남긴다 — 종료는 종료 대기 한도 안에서 이력과 기록을 기다린다")
    void 서버가_내려가도_남긴다() {
        // given — 쓰기를 마치고 더 기다리는 중이다
        SyncJobs jobs = 잡스();
        AtomicBoolean 썼다 = new AtomicBoolean();
        jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC, AuditKind.SYNC,
                (started, 기록) -> Mono.defer(() -> {
                    김에게_줬다(기록);
                    썼다.set(true);
                    return Mono.<Mono<SyncOutcome>>never();
                }), run -> {
                }).block();
        await().atMost(Duration.ofSeconds(5)).untilTrue(썼다);

        // when
        jobs.shutdown();

        // then
        assertThat(runs.finished).singleElement()
                .satisfies(run -> assertThat(run.message()).isEqualTo("서버 종료로 중단"));
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("이력의 시각은 반납 뒤가 아니라 락 안의 일이 끝난 때다")
    void 시각은_락_안의_일이_끝난_때다() {
        // given — 반납하는 순간 시계가 1분 간다. 이력은 반납 뒤에 쓴다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(지금);
        SyncJobs jobs = new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1),
                new AuditRecorder(auditLog, AuditObserver.NOOP, 시계가_가는_락.clock()));

        // when
        SyncRun run = 건다(jobs, (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(지금.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(지금));
    }

    @Test
    @DisplayName("기한으로 멈춘 회차의 시각도 반납 전, 락 안에서 끝난 때다")
    void 기한으로_멈춘_회차의_시각도_반납_전이다() {
        // given
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(지금);
        SyncJobs jobs = new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMillis(200),
                new AuditRecorder(auditLog, AuditObserver.NOOP, 시계가_가는_락.clock()));

        // when
        SyncRun run = 건다(jobs, (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>never();
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(지금.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(지금));
    }

    @Test
    @DisplayName("이력 쓰기가 실패해도 회차의 상태는 그대로이고, 메시지 앞과 지표에 빠진 줄이 남는다")
    void 이력_쓰기가_실패하면_회차_메시지에_붙는다() {
        // given
        auditLog.failWith = new IllegalStateException("DynamoDB 장애");

        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).startsWith("감사 기록 1건 실패");
        assertThat(빠진_줄_지표).hasValue(1);
    }

    @Test
    @DisplayName("일이 종류를 바꾸면 그 종류로 남긴다 — 장부를 훑어 맞추는 LDAP 동기화")
    void 일이_종류를_바꾸면_그_종류로_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            기록.kind(AuditKind.LEDGER_ALIGNMENT);
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.LEDGER_ALIGNMENT, run.runId())));
    }
}

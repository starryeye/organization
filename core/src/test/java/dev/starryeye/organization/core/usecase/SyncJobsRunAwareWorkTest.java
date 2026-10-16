package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.RunAudit;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 일이 연 실행 기록과 변경 모음을 받는 길 — 회차 id 를 이력의 원인으로 적는다(설계 2026-10-10 §4.2, 2026-10-15 §3). */
class SyncJobsRunAwareWorkTest {

    private final FakeSyncRunRepository runs = new FakeSyncRunRepository(Instant.parse("2026-10-10T03:00:00Z"));
    private final FakeMutationLock lock = new FakeMutationLock();
    private final SyncJobs jobs = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1));

    @Test
    @DisplayName("일은 연 실행 기록과 회차의 변경 모음을 받고, 반납 뒤 할 일은 락을 반납한 뒤에 돈다")
    void 일은_연_실행_기록을_받는다() {
        // given
        AtomicReference<String> 받은_기록 = new AtomicReference<>();
        AtomicReference<RunAudit> 받은_모음 = new AtomicReference<>();
        AtomicBoolean 반납_뒤에_락을_쥠 = new AtomicBoolean(true);

        // when
        SyncRun started = jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC,
                AuditKind.SYNC, (run, 기록) -> {
                    받은_기록.set(run.runId());
                    받은_모음.set(기록);
                    return Mono.just(Mono.fromSupplier(() -> {
                        반납_뒤에_락을_쥠.set(lock.isHeld());
                        return SyncOutcome.noChange();
                    }));
                }, run -> {
                }).block();
        SyncRun finished = runs.awaitFinished(started.runId());

        // then
        assertThat(받은_기록.get()).isEqualTo(started.runId());
        assertThat(받은_모음.get().kind()).isEqualTo(AuditKind.SYNC);
        assertThat(반납_뒤에_락을_쥠).isFalse();
        assertThat(finished.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }
}

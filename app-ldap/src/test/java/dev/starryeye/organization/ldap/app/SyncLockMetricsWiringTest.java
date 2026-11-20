package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.SyncJobs;
import dev.starryeye.organization.storage.DynamoDbProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code UseCaseConfig.syncJobs} 가 {@link SyncMetrics} 를 락 관찰자로 꽂는다 (설계 2026-11-20 L1).
 * 꽂지 않으면 LDAP 회차의 락 경합이 지표에 남지 않는다.
 */
class SyncLockMetricsWiringTest {

    private static final Instant 지금 = Instant.parse("2026-11-20T03:00:00Z");

    @Test
    @DisplayName("락이 잡혀 있어 못 띄운 회차가 sync.lock.contended 를 올린다")
    void 못_띄운_회차가_경합을_센다() {
        // given
        var registry = new SimpleMeterRegistry();
        var metrics = new SyncMetrics(registry);
        var lock = new FakeMutationLock();
        lock.failAcquire = true;
        var clock = Clock.fixed(지금, ZoneOffset.UTC);
        SyncJobs jobs = new UseCaseConfig().syncJobs(new FakeSyncRunRepository(지금), lock, new DynamoDbProperties(),
                new SyncProperties(), new AuditRecorder(new FakeAuditLog(), lines -> {
                }, clock), clock, metrics);

        // when
        assertThatThrownBy(() -> jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL,
                MutationLock.LockPurpose.SYNC, AuditKind.SYNC,
                (run, audit) -> Mono.just(Mono.just(SyncOutcome.noChange())), run -> {
                }).block())
                .isInstanceOf(LockUnavailableException.class);

        // then
        assertThat(registry.find("sync.lock.contended").counter().count()).isEqualTo(1);
        jobs.shutdown();
    }
}

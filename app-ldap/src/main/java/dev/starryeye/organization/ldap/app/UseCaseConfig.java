package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.port.AuditLog;
import dev.starryeye.organization.core.port.DailyJobClaims;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import dev.starryeye.organization.core.usecase.SyncJobs;
import dev.starryeye.organization.storage.DynamoDbProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class UseCaseConfig {

    /**
     * 빈 이름이 {@code syncProperties} 여야 한다 — {@code SyncScheduler} 의 {@code @Scheduled(cron = "#{@syncProperties.cron}")} 가 이 이름으로 참조한다
     * (설계 2026-10-27 §5). 값은 클래스의 {@code @ConfigurationProperties("sync")} 가 바인딩한다.
     */
    @Bean
    public SyncProperties syncProperties() {
        return new SyncProperties();
    }

    @Bean
    public DeletionGuard deletionGuard(SyncProperties properties) {
        var config = properties.getDeletionGuard();
        return new DeletionGuard(new DeletionGuardPolicy(
                config.isEnabled(), config.getThresholdRatio(), config.getMinBaseline()));
    }

    /**
     * 동기화·재적재를 요청과 떼어 돌리고(설계 2026-09-29 §4·§5), 작업 락을 클러스터 전체에서 하나로 잡는다(설계 2026-09-30 §3) — app-ldap 을 여러 대
     * 띄워도 한 번에 하나만 돈다. 락을 잡은 작업이 시작할 때 죽은 인스턴스가 남긴 RUNNING 기록을 "비정상 종료로 중단"으로 닫는다.
     * 앱이 내려갈 때 도는 작업을 FAILED("서버 종료로 중단")로 기록하고 락을 반납한다 — 실행 기록 저장소를 거쳐 DynamoDB 클라이언트에 기대므로
     * 스프링이 클라이언트보다 먼저 닫는다. 회차의 변경 이력도 여기서 남긴다(설계 2026-10-15 §3).
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             SyncProperties properties, AuditRecorder auditRecorder) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), LockObserver.NOOP, properties.getJobTimeout(),
                auditRecorder);
    }

    /**
     * 하루 1회 작업(만료 스냅샷 정리)을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1). 표지의 임대는 잡은 때 + {@code sync.job-timeout} + 5분이다
     * (설계 2026-10-27 §9).
     */
    @Bean
    public DailyOnce dailyOnce(DailyJobClaims claims, Clock clock, SyncProperties properties) {
        return new DailyOnce(claims, clock, properties.getJobTimeout());
    }

    /** 변경 이력을 남긴다(설계 2026-10-10 §4). 쓰지 못한 줄은 {@code audit.write.failures} 로 센다(§4.5). */
    @Bean
    public AuditRecorder auditRecorder(AuditLog auditLog, MeterRegistry registry, Clock clock) {
        return new AuditRecorder(auditLog, lines -> registry.counter("audit.write.failures").increment(lines), clock);
    }

    @Bean
    public FullSyncUseCase fullSyncUseCase(DirectorySnapshotSource source,
                                           TupleSnapshotRepository snapshots,
                                           DirectoryStateRepository state,
                                           RelationTupleWriter writer,
                                           RelationTupleScanner scanner,
                                           DeletionGuard guard,
                                           SyncJobs jobs,
                                           Clock clock) {
        return new FullSyncUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
    }

    @Bean
    public RebuildUseCase rebuildUseCase(DirectorySnapshotSource source,
                                         TupleSnapshotRepository snapshots,
                                         DirectoryStateRepository state,
                                         RelationTupleWriter writer,
                                         RelationTupleScanner scanner,
                                         DeletionGuard guard,
                                         SyncJobs jobs,
                                         Clock clock) {
        return new RebuildUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
    }
}

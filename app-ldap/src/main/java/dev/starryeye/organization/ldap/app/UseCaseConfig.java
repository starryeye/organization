package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(SyncProperties.class)
public class UseCaseConfig {

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
     * 스프링이 클라이언트보다 먼저 닫는다.
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             SyncProperties properties) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), LockObserver.NOOP, properties.getJobTimeout());
    }

    /** 하루 1회 작업(만료 스냅샷 정리)을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1). */
    @Bean
    public DailyOnce dailyOnce(DailyJobClaims claims, Clock clock) {
        return new DailyOnce(claims, clock);
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

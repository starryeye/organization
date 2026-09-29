package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.ScimRebuildUseCase;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import dev.starryeye.organization.core.usecase.SyncJobs;
import dev.starryeye.organization.storage.DynamoDbProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ScimUseCaseConfig {

    @Bean
    public ScimSyncMetrics scimSyncMetrics(MeterRegistry registry) {
        return new ScimSyncMetrics(registry);
    }

    @Bean
    public IncrementalSyncUseCase incrementalSyncUseCase(DirectoryStateRepository state,
                                                          RelationTupleWriter writer,
                                                          RelationTupleChecker checker,
                                                          MutationLock lock,
                                                          DynamoDbProperties dynamoDb,
                                                          ScimSyncMetrics metrics) {
        return new IncrementalSyncUseCase(state, writer, checker, lock,
                dynamoDb.getLockAcquireTimeout(), metrics, metrics);
    }

    /**
     * 재적재를 요청과 떼어 돌린다(설계 2026-09-29 §4·§5). {@code sync.job-timeout} 을 넘기면 멈춰 FAILED 로 기록하고 락을 반납한다.
     * 앱이 내려갈 때 도는 재적재를 FAILED("서버 종료로 중단")로 기록하고 락을 반납한다 — DynamoDB 클라이언트보다 먼저 닫힌다
     * (이 빈이 실행 기록 저장소를 거쳐 클라이언트에 기대므로 스프링이 먼저 닫는다).
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, @Value("${sync.job-timeout:30m}") Duration jobTimeout) {
        return new SyncJobs(runs, jobTimeout);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 RelationTupleScanner scanner,
                                                 TupleSnapshotRepository snapshots,
                                                 MutationLock lock,
                                                 DynamoDbProperties dynamoDb,
                                                 ScimSyncMetrics metrics,
                                                 SyncJobs jobs,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, scanner, snapshots, lock,
                dynamoDb.getLockRenewInterval(), metrics, jobs, clock);
    }

    @Bean
    public SnapshotArchiveUseCase snapshotArchiveUseCase(DirectoryStateRepository state,
                                                          RelationTupleChecker checker,
                                                          TupleSnapshotRepository snapshots,
                                                          SyncRunRepository runs,
                                                          Clock clock) {
        return new SnapshotArchiveUseCase(state, checker, snapshots, runs, clock);
    }
}

package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.DailyJobClaims;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.DailyOnce;
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
     * 재적재를 요청과 떼어 돌린다(설계 2026-09-29 §4·§5). 작업 락(SCIM 쓰기와 같은 락)을 잡고 갱신하고 반납하는 일도 여기서 한다(설계 2026-09-30 §3) —
     * 락 대기·리스 상실 지표는 SCIM 쓰기와 같은 {@link ScimSyncMetrics} 로 남긴다. 앱이 내려갈 때 도는 재적재를 FAILED("서버 종료로 중단")로 기록하고
     * 락을 반납한다 — DynamoDB 클라이언트보다 먼저 닫힌다(이 빈이 실행 기록 저장소를 거쳐 클라이언트에 기대므로 스프링이 먼저 닫는다).
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             ScimSyncMetrics metrics, @Value("${sync.job-timeout:30m}") Duration jobTimeout) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), metrics, jobTimeout);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 RelationTupleScanner scanner,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncJobs jobs,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, scanner, snapshots, jobs, clock);
    }

    @Bean
    public SnapshotArchiveUseCase snapshotArchiveUseCase(DirectoryStateRepository state,
                                                          RelationTupleChecker checker,
                                                          TupleSnapshotRepository snapshots,
                                                          SyncRunRepository runs,
                                                          MutationLock lock,
                                                          Clock clock) {
        return new SnapshotArchiveUseCase(state, checker, snapshots, runs, lock, clock);
    }

    /** 하루 1회 작업(아카이빙·만료 스냅샷 정리)을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1). */
    @Bean
    public DailyOnce dailyOnce(DailyJobClaims claims, Clock clock) {
        return new DailyOnce(claims, clock);
    }
}

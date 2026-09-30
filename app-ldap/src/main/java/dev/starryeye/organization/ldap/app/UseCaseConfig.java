package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import dev.starryeye.organization.core.usecase.SyncJobs;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

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

    @Bean
    public SyncExecutionGuard syncExecutionGuard() {
        return new SyncExecutionGuard();
    }

    /**
     * 동기화·재적재를 요청과 떼어 돌린다(설계 2026-09-29 §4·§5). {@code sync.job-timeout} 을 넘기면 멈춰 FAILED 로 기록하고 가드를
     * 푼다. 앱이 내려갈 때 도는 작업을 FAILED("서버 종료로 중단")로 기록한다 — 실행 기록 저장소를 거쳐 DynamoDB 클라이언트에 기대므로
     * 스프링이 클라이언트보다 먼저 닫는다.
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, SyncProperties properties) {
        return new SyncJobs(runs, properties.getJobTimeout());
    }

    /** 테이블이 준비된 뒤에 돈다 — 첫 배포에서 테이블이 없으면 읽기가 실패한다(실패해도 시작은 막지 않는다). */
    @Bean
    @DependsOn("tableInitializer")
    public InterruptedRunCleanup interruptedRunCleanup(SyncRunRepository runs) {
        return new InterruptedRunCleanup(runs);
    }

    @Bean
    public FullSyncUseCase fullSyncUseCase(DirectorySnapshotSource source,
                                           TupleSnapshotRepository snapshots,
                                           DirectoryStateRepository state,
                                           RelationTupleWriter writer,
                                           DeletionGuard guard,
                                           SyncJobs jobs,
                                           Clock clock) {
        return new FullSyncUseCase(source, snapshots, state, writer, guard, jobs, clock);
    }

    @Bean
    public RebuildUseCase rebuildUseCase(DirectorySnapshotSource source,
                                         TupleSnapshotRepository snapshots,
                                         DirectoryStateRepository state,
                                         RelationTupleWriter writer,
                                         RelationTupleScanner scanner,
                                         SyncJobs jobs,
                                         Clock clock) {
        return new RebuildUseCase(source, snapshots, state, writer, scanner, jobs, clock);
    }
}

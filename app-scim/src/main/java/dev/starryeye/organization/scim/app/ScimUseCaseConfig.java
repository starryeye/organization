package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.port.AuditLog;
import dev.starryeye.organization.core.port.DailyJobClaims;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TraceIds;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.ScimRebuildUseCase;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import dev.starryeye.organization.core.usecase.SyncJobs;
import dev.starryeye.organization.storage.DynamoDbProperties;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Slf4j
@Configuration
public class ScimUseCaseConfig {

    @Bean
    public ScimSyncMetrics scimSyncMetrics(MeterRegistry registry) {
        return new ScimSyncMetrics(registry);
    }

    /** 변경 이력을 남긴다(설계 2026-10-10 §4). 쓰지 못한 줄은 {@code audit.write.failures} 로 센다(§4.5). */
    @Bean
    public AuditRecorder auditRecorder(AuditLog auditLog, MeterRegistry registry, Clock clock) {
        return new AuditRecorder(auditLog, lines -> registry.counter("audit.write.failures").increment(lines), clock);
    }

    /**
     * 요청의 traceId — SCIM 쓰기 이력의 원인 id 다(설계 2026-10-10 §4.2). SCIM 쓰기는 요청과 떼어 돌지만 요청의 Reactor Context 를 이어받는다
     * ({@code IncrementalSyncUseCase.withLock}). 그 Context 의 관측을 잠깐 ThreadLocal 로 옮겨 지금 span 을 읽는다 — 로그의 traceId 와 같은 값이다.
     *
     * <p>Context 에 관측이 없으면 ThreadLocal 도 비운다({@code clearMissing}) — 그 스레드에 남아 있던 다른 요청의 span 을 읽지 않는다. 읽다 던지면
     * 경고만 남기고 모른다(null)고 답한다 — 원인 id 는 임의 UUID 가 되고, 이력 때문에 SCIM 쓰기가 막히지 않는다.
     */
    @Bean
    public TraceIds traceIds(Tracer tracer) {
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().clearMissing(true).build();
        return context -> {
            try (ContextSnapshot.Scope ignored = snapshots.captureFrom(context).setThreadLocals()) {
                Span span = tracer.currentSpan();
                return span == null ? null : span.context().traceId();
            } catch (RuntimeException e) {
                log.warn("요청의 traceId 를 읽지 못했다 — 이력의 원인 id 는 임의 값이 된다", e);
                return null;
            }
        };
    }

    @Bean
    public IncrementalSyncUseCase incrementalSyncUseCase(DirectoryStateRepository state,
                                                          RelationTupleWriter writer,
                                                          RelationTupleChecker checker,
                                                          MutationLock lock,
                                                          DynamoDbProperties dynamoDb,
                                                          ScimSyncMetrics metrics,
                                                          AuditRecorder auditRecorder,
                                                          TraceIds traceIds) {
        return new IncrementalSyncUseCase(state, writer, checker, lock,
                dynamoDb.getLockAcquireTimeout(), dynamoDb.getLockRenewInterval(), metrics, metrics,
                auditRecorder, traceIds, dynamoDb.getLockTtl());
    }

    /**
     * 재적재를 요청과 떼어 돌린다(설계 2026-09-29 §4·§5). 작업 락(SCIM 쓰기와 같은 락)을 잡고 갱신하고 반납하는 일도 여기서 한다(설계 2026-09-30 §3) —
     * 락 대기·리스 상실 지표는 SCIM 쓰기와 같은 {@link ScimSyncMetrics} 로 남긴다. 앱이 내려갈 때 도는 재적재를 FAILED("서버 종료로 중단")로 기록하고
     * 락을 반납한다 — DynamoDB 클라이언트보다 먼저 닫힌다(이 빈이 실행 기록 저장소를 거쳐 클라이언트에 기대므로 스프링이 먼저 닫는다).
     * 회차의 변경 이력도 여기서 남긴다(설계 2026-10-15 §3).
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             ScimSyncMetrics metrics, @Value("${sync.job-timeout:30m}") Duration jobTimeout,
                             AuditRecorder auditRecorder) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), metrics, jobTimeout, auditRecorder);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 RelationTupleScanner scanner,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncJobs jobs,
                                                 Clock clock,
                                                 AuditRecorder auditRecorder) {
        return new ScimRebuildUseCase(state, writer, scanner, snapshots, jobs, clock, auditRecorder);
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

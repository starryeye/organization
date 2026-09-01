package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.ScimRebuildUseCase;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import dev.starryeye.organization.storage.DynamoDbProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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
                                                          ScimSyncMetrics driftMetrics) {
        int acquireRetries = (int) (dynamoDb.getLockAcquireTimeout().toMillis() / 200);
        return new IncrementalSyncUseCase(state, writer, checker, lock, acquireRetries, driftMetrics);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncRunRepository runs,
                                                 MutationLock lock,
                                                 DynamoDbProperties dynamoDb,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, snapshots, runs, lock,
                dynamoDb.getLockRenewInterval(), clock);
    }

    @Bean
    public SnapshotArchiveUseCase snapshotArchiveUseCase(DirectoryStateRepository state,
                                                          TupleSnapshotRepository snapshots,
                                                          SyncRunRepository runs,
                                                          Clock clock) {
        return new SnapshotArchiveUseCase(state, snapshots, runs, clock);
    }
}

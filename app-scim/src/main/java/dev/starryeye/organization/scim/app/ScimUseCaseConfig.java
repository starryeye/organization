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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ScimUseCaseConfig {

    @Bean
    public IncrementalSyncUseCase incrementalSyncUseCase(DirectoryStateRepository state,
                                                          RelationTupleWriter writer,
                                                          RelationTupleChecker checker,
                                                          MutationLock lock,
                                                          DynamoDbProperties dynamoDb) {
        int acquireRetries = (int) (dynamoDb.getLockAcquireTimeout().toMillis() / 200);
        return new IncrementalSyncUseCase(state, writer, checker, lock, acquireRetries);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncRunRepository runs,
                                                 MutationLock lock,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, snapshots, runs, lock, clock);
    }

    @Bean
    public SnapshotArchiveUseCase snapshotArchiveUseCase(DirectoryStateRepository state,
                                                          TupleSnapshotRepository snapshots,
                                                          SyncRunRepository runs,
                                                          Clock clock) {
        return new SnapshotArchiveUseCase(state, snapshots, runs, clock);
    }
}

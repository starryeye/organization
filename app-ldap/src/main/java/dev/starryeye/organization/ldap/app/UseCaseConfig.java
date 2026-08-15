package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
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

    @Bean
    public SyncExecutionGuard syncExecutionGuard() {
        return new SyncExecutionGuard();
    }

    @Bean
    public FullSyncUseCase fullSyncUseCase(DirectorySnapshotSource source,
                                           TupleSnapshotRepository snapshots,
                                           DirectoryStateRepository state,
                                           RelationTupleWriter writer,
                                           SyncRunRepository runs,
                                           DeletionGuard guard,
                                           Clock clock) {
        return new FullSyncUseCase(source, snapshots, state, writer, runs, guard, clock);
    }
}

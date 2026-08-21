package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.MutationGate;
import dev.starryeye.organization.core.usecase.ScimRebuildUseCase;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ScimUseCaseConfig {

    /**
     * 재적재가 도는 동안 SCIM 변경을 막는 문. 인스턴스 하나 안에서만 유효하므로
     * 여러 대로 늘리면 저장소 수준 장치로 바꿔야 한다.
     */
    @Bean
    public MutationGate mutationGate() {
        return new MutationGate();
    }

    @Bean
    public IncrementalSyncUseCase incrementalSyncUseCase(DirectoryStateRepository state,
                                                          RelationTupleWriter writer,
                                                          MutationGate gate) {
        return new IncrementalSyncUseCase(state, writer, gate);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncRunRepository runs,
                                                 MutationGate gate,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, snapshots, runs, gate, clock);
    }

    @Bean
    public SnapshotArchiveUseCase snapshotArchiveUseCase(DirectoryStateRepository state,
                                                          TupleSnapshotRepository snapshots,
                                                          SyncRunRepository runs,
                                                          Clock clock) {
        return new SnapshotArchiveUseCase(state, snapshots, runs, clock);
    }
}

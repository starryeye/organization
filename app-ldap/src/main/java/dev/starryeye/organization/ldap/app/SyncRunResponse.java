package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;

import java.time.Instant;

public record SyncRunResponse(
        String runId,
        String source,
        String trigger,
        Instant startedAt,
        Instant finishedAt,
        String status,
        int writtenCount,
        int deletedCount,
        int failureCount,
        String snapshotId,
        String message
) {

    public static SyncRunResponse from(SyncRun run) {
        return new SyncRunResponse(
                run.runId(),
                run.source() == null ? null : run.source().name(),
                run.trigger() == null ? null : run.trigger().name(),
                run.startedAt(),
                run.finishedAt(),
                run.status() == null ? null : run.status().name(),
                run.writtenCount(),
                run.deletedCount(),
                run.failureCount(),
                run.snapshotId(),
                run.message());
    }
}

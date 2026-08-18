package dev.starryeye.organization.core.model;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record SyncRun(
        String runId,
        SyncSource source,
        SyncTrigger trigger,
        Instant startedAt,
        Instant finishedAt,
        SyncStatus status,
        int writtenCount,
        int deletedCount,
        int failureCount,
        String snapshotId,
        String message
) {

    public static SyncRun started(String runId, SyncSource source, SyncTrigger trigger, Instant at) {
        return SyncRun.builder()
                .runId(runId)
                .source(source)
                .trigger(trigger)
                .startedAt(at)
                .status(SyncStatus.RUNNING)
                .build();
    }

    public SyncRun finished(SyncOutcome outcome, Instant at) {
        return this.toBuilder()
                .finishedAt(at)
                .status(outcome.status())
                .writtenCount(outcome.writtenCount())
                .deletedCount(outcome.deletedCount())
                .failureCount(outcome.failureCount())
                .snapshotId(outcome.snapshotId())
                .message(outcome.message())
                .build();
    }
}

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
        String message,
        String requestedBy
) {

    /**
     * 막 시작한 기록. {@code requestedBy} 는 이 회차를 건 행위자다(설계 2026-10-28 인증 §5) — {@code Actor} 의 값이다. 속성이 없는 옛 기록은 null 이다
     * (운영 배포 전이라 이관하지 않는다).
     */
    public static SyncRun started(String runId, SyncSource source, SyncTrigger trigger, Instant at, String requestedBy) {
        return SyncRun.builder()
                .runId(runId)
                .source(source)
                .trigger(trigger)
                .startedAt(at)
                .status(SyncStatus.RUNNING)
                .requestedBy(requestedBy)
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

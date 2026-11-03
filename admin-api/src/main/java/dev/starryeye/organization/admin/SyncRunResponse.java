package dev.starryeye.organization.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        String message,
        /** 이 회차를 건 행위자({@code client:<sub>}·{@code system:scheduler} 등). 모르는 기록(속성이 없는 옛 기록)은 칸이 없다. */
        @JsonInclude(JsonInclude.Include.NON_NULL) String requestedBy
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
                run.message(),
                run.requestedBy());
    }
}

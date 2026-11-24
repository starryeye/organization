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
        @JsonInclude(JsonInclude.Include.NON_NULL) String requestedBy,
        /** 가드가 멈춘 회차의 지우려던 것의 내역(설계 2026-11-20 §3.1). 내역이 없는 기록과 목록 응답은 칸이 없다. */
        @JsonInclude(JsonInclude.Include.NON_NULL) GuardHoldResponse guardHold,
        /** 재활성 가드가 보류한 직원의 내역(설계 2026-11-24 R7). 보류하지 않은 기록과 목록 응답은 칸이 없다. */
        @JsonInclude(JsonInclude.Include.NON_NULL) GuardHoldResponse.Sample reactivationHold
) {

    /** 단건·202 응답 — 멈춤 내역을 싣는다. */
    public static SyncRunResponse from(SyncRun run) {
        var hold = run.reactivationHold();
        return of(run, run.guardHold() == null ? null : GuardHoldResponse.from(run.guardHold()),
                hold == null ? null : new GuardHoldResponse.Sample(hold.count(), hold.ids()));
    }

    /** 목록 응답 — 멈춤 내역은 한 건씩 열어 볼 때만 싣는다(설계 2026-11-20 §3.1). */
    public static SyncRunResponse listed(SyncRun run) {
        return of(run, null, null);
    }

    private static SyncRunResponse of(SyncRun run, GuardHoldResponse guardHold,
                                     GuardHoldResponse.Sample reactivationHold) {
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
                run.requestedBy(),
                guardHold,
                reactivationHold);
    }
}

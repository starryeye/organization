package dev.starryeye.organization.core.model;

public record SyncOutcome(
        SyncStatus status,
        int writtenCount,
        int deletedCount,
        int failureCount,
        String snapshotId,
        String message
) {

    public static SyncOutcome noChange() {
        return new SyncOutcome(SyncStatus.SUCCEEDED, 0, 0, 0, null, "변경 없음");
    }

    public static SyncOutcome succeeded(TupleWriteResult result, String snapshotId) {
        return new SyncOutcome(SyncStatus.SUCCEEDED,
                result.written().size(), result.deleted().size(), 0, snapshotId, null);
    }

    public static SyncOutcome partial(TupleWriteResult result, String snapshotId) {
        return new SyncOutcome(SyncStatus.PARTIAL,
                result.written().size(), result.deleted().size(), result.failures().size(), snapshotId,
                result.failures().size() + "건의 튜플 적용에 실패했습니다. 다음 동기화가 다시 시도합니다");
    }

    /**
     * 쓰기가 연속 실패 차단기로 멈췄다(설계 2026-09-29 §5). 멈췄지만 기록 규칙은 지켰다 — 스냅샷이 장부를 따라간다. 건수는 멈추기 전까지
     * 실제로 나간 것이고(보내지 않은 줄은 실패로 센다), 멈췄다는 사실은 FAILED 로 남긴다.
     */
    public static SyncOutcome stopped(TupleWriteResult result, String snapshotId, String message) {
        return new SyncOutcome(SyncStatus.FAILED,
                result.written().size(), result.deleted().size(), result.failures().size(), snapshotId, message);
    }

    public static SyncOutcome aborted(String message) {
        return new SyncOutcome(SyncStatus.ABORTED, 0, 0, 0, null, message);
    }

    public static SyncOutcome failed(String message) {
        return new SyncOutcome(SyncStatus.FAILED, 0, 0, 0, null, message);
    }
}

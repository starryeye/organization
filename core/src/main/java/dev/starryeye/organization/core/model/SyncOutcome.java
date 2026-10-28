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

    /**
     * 장부 맞추기가 지우기 전 확인에서 전부 보류로 끝났다(설계 2026-10-27 §12). 보류했지만 D 는 이미 썼다 — 쓴 수와 실패 수는 결과대로 남기고(그래서
     * {@code sync.tuples.written} 도 센다) 지운 수는 0 이다. 스냅샷은 남기지 않으므로 없다. 상태는 ABORTED 다.
     */
    public static SyncOutcome held(TupleWriteResult result, String reason) {
        return new SyncOutcome(SyncStatus.ABORTED,
                result.written().size(), 0, result.failures().size(), null, reason);
    }

    /**
     * 삭제 가드가 걸렸지만 디렉터리가 막았다고 명시한 회수만 반영했다(설계 2026-10-20 §5.3). 가드가 걸린 회차라 ABORTED 이고, 건수는 실제로 나간
     * 것이다. 스냅샷은 남겼다 — 현재상태는 바꾸지 않았다.
     */
    public static SyncOutcome revokedOnly(TupleWriteResult result, String snapshotId, String message) {
        return new SyncOutcome(SyncStatus.ABORTED,
                result.written().size(), result.deleted().size(), result.failures().size(), snapshotId, message);
    }

    public static SyncOutcome failed(String message) {
        return new SyncOutcome(SyncStatus.FAILED, 0, 0, 0, null, message);
    }

    /** 이 결론 끝에 한마디를 잇는다 — "현재상태는 디렉터리대로 교체했다"처럼 회차가 한 일을 메시지에 남길 때(설계 2026-10-27 §12). */
    public SyncOutcome withSuffix(String suffix) {
        String combined = message == null ? suffix : message + suffix;
        return new SyncOutcome(status, writtenCount, deletedCount, failureCount, snapshotId, combined);
    }

    /** 이 결론 앞에 한마디를 붙인다 — "기준선 의심"처럼 회차의 사정을 기록에 남길 때(설계 2026-09-30 §4.2). */
    public SyncOutcome withNote(String note) {
        String combined = message == null ? note : note + " / " + message;
        return new SyncOutcome(status, writtenCount, deletedCount, failureCount, snapshotId, combined);
    }
}

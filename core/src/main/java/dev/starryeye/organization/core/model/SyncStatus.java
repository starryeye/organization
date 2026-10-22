package dev.starryeye.organization.core.model;

public enum SyncStatus {
    RUNNING,
    SUCCEEDED,
    /** 일부 튜플 적용에 실패. 다음 동기화가 자동으로 다시 잡는다 */
    PARTIAL,
    /** 삭제 가드가 걸렸다 — 비활성 회수만 반영했을 수 있다(설계 2026-10-20 §5.3). 부여·나머지 지우기·현재상태 교체는 보류했다 */
    ABORTED,
    FAILED
}

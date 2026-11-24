package dev.starryeye.organization.core.model;

public enum SyncStatus {
    RUNNING,
    SUCCEEDED,
    /** 일부 튜플 적용에 실패. 다음 동기화가 자동으로 다시 잡는다 */
    PARTIAL,
    /** 가드가 걸렸다 — 무엇을 반영·보류했는지는 메시지와 두 내역(guardHold·reactivationHold)에 있다(설계 2026-11-24 R6) */
    ABORTED,
    FAILED
}

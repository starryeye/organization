package dev.starryeye.organization.core.model;

public enum SyncStatus {
    RUNNING,
    SUCCEEDED,
    /** 일부 튜플 적용에 실패. 다음 동기화가 자동으로 다시 잡는다 */
    PARTIAL,
    /** 삭제 가드가 발동해 OpenFGA 를 건드리지 않았다 */
    ABORTED,
    FAILED
}

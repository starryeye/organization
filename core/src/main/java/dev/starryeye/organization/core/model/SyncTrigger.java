package dev.starryeye.organization.core.model;

public enum SyncTrigger {
    /** 스케줄러가 기동 */
    SCHEDULED,
    /** 관리 API 수동 실행 */
    MANUAL,
    /** 관리 API 수동 실행 + 삭제 가드 우회 */
    FORCED,
    /** 전체 재적재 */
    REBUILD,
    /** SCIM 인스턴스의 일 1회 스냅샷 아카이빙 */
    ARCHIVE,
    /** 조직도까지 전부 비우는 초기화. 되돌릴 수 없고 IdP 재푸시가 뒤따라야 한다 */
    RESET
}

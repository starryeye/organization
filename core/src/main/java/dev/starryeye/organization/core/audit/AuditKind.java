package dev.starryeye.organization.core.audit;

/** 이력 한 줄을 만든 사건의 종류(설계 2026-10-10 §3.3). */
public enum AuditKind {
    /** LDAP 전체 동기화 — 기준선과 비교해 맞춘 회차 */
    SYNC,
    /** 재적재 — LDAP 재적재, SCIM 재적재(tuples·wipe) */
    REBUILD,
    /** 지난 회차가 기록 전에 멈춰 장부를 훑어 맞춘 LDAP 동기화 회차 */
    LEDGER_ALIGNMENT,
    /** SCIM 쓰기 요청 하나 */
    SCIM_REQUEST
}

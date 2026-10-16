package dev.starryeye.organization.core.audit;

import java.util.Objects;

/**
 * 회차 하나의 변경 모음(설계 2026-10-15 §3). {@code SyncJobs} 가 회차를 열 때 만들어 일에 넘기고, 일은 쓰기가 끝나는 즉시 {@link #changes()} 에 더한다.
 * 회차가 값·오류·취소 무엇으로 끝나든 {@code SyncJobs} 가 락을 반납한 뒤 이 모음을 종류와 함께 남긴다.
 *
 * <p>종류는 부르는 쪽이 기본값을 준다. LDAP 동기화가 장부 맞추기로 가면 일이 {@link AuditKind#LEDGER_ALIGNMENT} 로 바꾼다.
 */
public final class RunAudit {

    private final AuditChanges changes = new AuditChanges();
    private volatile AuditKind kind;

    public RunAudit(AuditKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** 이 회차가 실제로 한 변경. 여러 스레드에서 더할 수 있다. */
    public AuditChanges changes() {
        return changes;
    }

    public AuditKind kind() {
        return kind;
    }

    /** 이 회차가 남길 종류를 바꾼다 — 기준선을 믿지 못해 장부를 훑어 맞추는 LDAP 동기화가 부른다. */
    public void kind(AuditKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }
}

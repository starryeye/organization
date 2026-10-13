package dev.starryeye.organization.core.audit;

import java.time.Instant;

/** 읽은 이력 한 줄 — 언제, 무엇 때문에, 무엇이 바뀌었나(설계 2026-10-10 §5). */
public record AuditRecord(Instant at, AuditCause cause, AuditEntry entry) {
}

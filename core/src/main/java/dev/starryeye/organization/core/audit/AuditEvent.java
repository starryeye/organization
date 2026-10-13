package dev.starryeye.organization.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 사건 하나 — 기록 시각, 원인, 대상별 줄. 저장소에 한 번에 넘긴다(설계 2026-10-10 §4.1). */
public record AuditEvent(Instant at, AuditCause cause, List<AuditEntry> entries) {

    public AuditEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(cause, "cause");
        entries = List.copyOf(entries);
    }
}

package dev.starryeye.organization.core.audit;

import dev.starryeye.organization.core.model.SyncSource;

import java.util.Objects;
import java.util.UUID;

/**
 * 이력 한 줄의 원인(설계 2026-10-10 §3.3). {@code id} 는 회차면 동기화 이력의 {@code runId}, SCIM 요청이면 그 요청의 traceId 다 — 동기화 이력과
 * 로그로 거슬러 가는 열쇠다.
 */
public record AuditCause(SyncSource source, AuditKind kind, String id) {

    public AuditCause {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /** 동기화·재적재 회차. */
    public static AuditCause run(SyncSource source, AuditKind kind, String runId) {
        return new AuditCause(source, kind, runId);
    }

    /**
     * SCIM 요청 하나. traceId 를 모르면(추적이 꺼진 테스트 등) 임의 값을 쓴다 — 정렬키가 원인 id 를 담아 비워 둘 수 없고, 같은 대상에 같은 밀리초로
     * 온 두 요청이 한 줄을 덮어쓰지 않아야 한다.
     */
    public static AuditCause scimRequest(String traceId) {
        String id = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        return new AuditCause(SyncSource.SCIM, AuditKind.SCIM_REQUEST, id);
    }
}

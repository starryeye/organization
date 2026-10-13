package dev.starryeye.organization.core.audit;

/**
 * 이력 쓰기가 실패해 빠진 줄을 알린다 — 지표 {@code audit.write.failures}(설계 2026-10-10 §4.5). core 가 Micrometer 를 알지 않게 콜백으로 받는다
 * ({@code IncrementalSyncUseCase.DriftObserver} 와 같은 이유).
 */
@FunctionalInterface
public interface AuditObserver {

    void writeFailed(int lines);

    AuditObserver NOOP = lines -> {
    };
}

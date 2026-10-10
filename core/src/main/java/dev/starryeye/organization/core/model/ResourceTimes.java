package dev.starryeye.organization.core.model;

import java.time.Instant;

/**
 * 리소스의 생성 시각과 마지막 변경 시각 — SCIM {@code meta.created}·{@code meta.lastModified}(RFC 7643 §3.1, 설계 2026-10-09 §4.1).
 * 저장본에 없으면 null 이다 — 이 설계 전에 만든 아이템에는 생성 시각이 없다(§3.3).
 */
public record ResourceTimes(Instant created, Instant lastModified) {

    /** 시각을 모를 때. 테스트 대역과, 시각 없이 그리는 응답이 쓴다. */
    public static final ResourceTimes UNKNOWN = new ResourceTimes(null, null);
}

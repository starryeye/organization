package dev.starryeye.organization.core.model;

import java.time.Instant;

/**
 * 표지판 줄 하나 — 그 값을 쥔 주인(직원·조직 아이디)과 차지한 시각(설계 2026-10-14 §3.1).
 */
public record UniqueClaim(String ownerId, Instant claimedAt) {
}

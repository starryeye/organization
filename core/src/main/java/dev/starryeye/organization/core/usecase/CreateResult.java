package dev.starryeye.organization.core.usecase;

/**
 * 생성(POST)의 결과 — 락 안에서 정한 id 와 반영 결과(설계 2026-11-05 I3). 후보 id 가 이미 다른 리소스의 것이면 무작위로 물러서므로
 * 부르는 쪽이 낸 후보가 아니라 이 {@code id} 가 응답의 id 다.
 */
public record CreateResult(String id, IncrementalSyncResult result) {
}

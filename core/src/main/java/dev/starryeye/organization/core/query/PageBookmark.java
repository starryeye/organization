package dev.starryeye.organization.core.query;

/**
 * 필터 없는 SCIM 목록의 다음 페이지를 이어 읽을 자리 (S-1 설계 §4.4).
 *
 * @param position     저장소가 만든 불투명 위치 — {@code DirectoryQueryRepository.listUsers} 의 {@code from}
 * @param totalResults 이 가져오기의 첫 페이지에서 센 전체 수. 이어지는 페이지가 그대로 쓴다
 */
public record PageBookmark(String position, long totalResults) {
}

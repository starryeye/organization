package dev.starryeye.organization.core.query;

/**
 * 직원 검색 결과 한 줄. 소속 정보는 담지 않는다 — 그건 상세 조회의 일이다.
 *
 * @param employeeId   정규화된 직원 아이디. 튜플에 실리는 값
 * @param userName     원본 계정명. 정규화 전 형태
 * @param displayName  사용자 표시명
 * @param active       현재 활성 상태인지
 */
public record UserSummary(String employeeId, String userName,
                          String displayName, boolean active) {
}

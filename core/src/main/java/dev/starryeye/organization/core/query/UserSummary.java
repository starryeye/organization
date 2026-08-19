package dev.starryeye.organization.core.query;

/** 직원 검색 결과 한 줄. 소속 정보는 담지 않는다 — 그건 상세 조회의 일이다. */
public record UserSummary(String employeeId, String userName,
                          String displayName, boolean active) {
}

package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * @param employeeId   정규화된 직원 아이디. 튜플에 실리는 값
 * @param userName     원본 계정명. 정규화 전 형태
 * @param displayName  사용자 표시명
 * @param email        직원 이메일 주소
 * @param active       현재 활성 상태인지
 * @param paths        소속 조직과 그 상위 계층 전부. 어디에도 안 속하면 빈 목록
 * @param truncated    경로가 상한을 넘어 잘렸는지
 */
public record EmployeeDetail(String employeeId, String userName, String displayName,
                             String email, boolean active,
                             List<AccessPath> paths, boolean truncated) {

    public EmployeeDetail {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}

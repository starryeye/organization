package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * @param paths     소속 조직과 그 상위 계층 전부. 어디에도 안 속하면 빈 목록
 * @param truncated 경로가 상한을 넘어 잘렸는지
 */
public record EmployeeDetail(String employeeId, String userName, String displayName,
                             String email, boolean active,
                             List<AccessPath> paths, boolean truncated) {

    public EmployeeDetail {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}

package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * @param orgCode            조직 코드
 * @param displayName        조직 표시명
 * @param externalId         외부 시스템 조직 식별자
 * @param assignedParentOrgCode 운영자가 이 조직에 지정한 상위 조직 코드(설계 2026-11-04 H11). 지정이 없으면 null — 칸은 늘 있다
 * @param ancestors          상위 계층 전부(IdP 멤버 연결과 지정 연결의 합집합). 최상위 조직이면 빈 목록
 * @param childOrganizations 직속 하위 조직만 (1 depth, 같은 합집합)
 * @param members            직속 소속 직원 첫 페이지
 */
public record OrganizationDetail(String orgCode, String displayName, String externalId,
                                 String assignedParentOrgCode,
                                 List<GroupSummary> ancestors,
                                 List<GroupSummary> childOrganizations,
                                 Page<OrgMember> members) {

    public OrganizationDetail {
        ancestors = ancestors == null ? List.of() : List.copyOf(ancestors);
        childOrganizations = childOrganizations == null ? List.of() : List.copyOf(childOrganizations);
    }
}

package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * @param ancestors          상위 계층 전부. 최상위 조직이면 빈 목록
 * @param childOrganizations 직속 하위 조직만 (1 depth)
 * @param members            직속 소속 직원 첫 페이지
 */
public record OrganizationDetail(String orgCode, String displayName, String externalId,
                                 List<GroupSummary> ancestors,
                                 List<GroupSummary> childOrganizations,
                                 Page<OrgMember> members) {

    public OrganizationDetail {
        ancestors = ancestors == null ? List.of() : List.copyOf(ancestors);
        childOrganizations = childOrganizations == null ? List.of() : List.copyOf(childOrganizations);
    }
}

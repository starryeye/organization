package dev.starryeye.organization.core.query;

/** 조직의 직속 소속 직원 한 줄. Check 실패 시 {@code openFgaCheck} 는 null. */
public record OrgMember(String employeeId, String displayName,
                        boolean active, Boolean openFgaCheck) {
}

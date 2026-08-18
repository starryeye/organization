package dev.starryeye.organization.core.model;

/**
 * @param id 직원 아이디. 튜플에 쓰이는 안정 식별자
 * @param externalId LDAP DN 또는 SCIM externalId (원본 보관)
 */
public record DirectoryUser(
        String id,
        String externalId,
        String userName,
        String displayName,
        String email,
        boolean active
) {
}

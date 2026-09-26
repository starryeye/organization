package dev.starryeye.organization.core.model;

import lombok.With;

/**
 * @param id 직원 아이디. 튜플에 쓰이는 안정 식별자
 * @param externalId LDAP DN 또는 SCIM externalId (원본 보관)
 * @param name RFC 이름 여섯 칸. 없으면 {@link PersonName#EMPTY}
 *
 * <p><b>필드 하나만 바꾼 복사는 {@code with…} 로 한다.</b> 6인자 생성자로 복사하면 이름이 조용히 사라진다 — 6인자
 * 생성자는 처음부터 이름이 없는 직원을 만들 때만 쓴다.
 */
@With
public record DirectoryUser(
        String id,
        String externalId,
        String userName,
        String displayName,
        String email,
        boolean active,
        PersonName name
) {

    public DirectoryUser {
        name = name == null ? PersonName.EMPTY : name;
    }

    /** 이름이 없는 직원. */
    public DirectoryUser(String id, String externalId, String userName, String displayName, String email,
                         boolean active) {
        this(id, externalId, userName, displayName, email, active, PersonName.EMPTY);
    }
}

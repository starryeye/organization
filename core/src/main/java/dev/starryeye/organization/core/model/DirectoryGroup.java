package dev.starryeye.organization.core.model;

import java.util.Set;

/**
 * @param id 조직코드. 튜플에 쓰이는 안정 식별자
 * @param displayName 조직명. 개편 때마다 바뀌므로 튜플에 절대 쓰지 않는다
 */
public record DirectoryGroup(
        String id,
        String externalId,
        String displayName,
        Set<MemberRef> members
) {

    public DirectoryGroup {
        members = members == null ? Set.of() : Set.copyOf(members);
    }
}

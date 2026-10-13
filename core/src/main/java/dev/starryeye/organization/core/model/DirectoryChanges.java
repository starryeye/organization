package dev.starryeye.organization.core.model;

import java.util.List;
import java.util.Set;

/**
 * 전체 교체({@code DirectoryStateRepository.replaceWith})가 실제로 쓴 것 — 저장본과 비교해 바뀐 직원·조직만 담는다(설계 2026-10-10 §4.3). 같은 조직도면
 * 비어 있다. 이력이 이것으로 조직도 변경을 남긴다.
 *
 * @param users  직원마다 이전·이후. 이전이 null 이면 생성, 이후가 null 이면 삭제다
 * @param groups 조직마다 이름표의 이전·이후와 들어오고 나간 멤버. 이전이 null 이면 생성, 이후가 null 이면 삭제다
 */
public record DirectoryChanges(List<UserDiff> users, List<GroupDiff> groups) {

    public DirectoryChanges {
        users = users == null ? List.of() : List.copyOf(users);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public static DirectoryChanges none() {
        return new DirectoryChanges(List.of(), List.of());
    }

    public boolean isEmpty() {
        return users.isEmpty() && groups.isEmpty();
    }

    public record UserDiff(DirectoryUser before, DirectoryUser after) {

        public UserDiff {
            if (before == null && after == null) {
                throw new IllegalArgumentException("이전과 이후가 모두 비었다");
            }
        }

        public String id() {
            return after != null ? after.id() : before.id();
        }
    }

    /** 이름표가 그대로이고 멤버만 바뀌었으면 이전과 이후가 같은 이름표다. */
    public record GroupDiff(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed) {

        public GroupDiff {
            if (before == null && after == null) {
                throw new IllegalArgumentException("이전과 이후가 모두 비었다");
            }
            added = added == null ? Set.of() : Set.copyOf(added);
            removed = removed == null ? Set.of() : Set.copyOf(removed);
        }

        public String id() {
            return after != null ? after.id() : before.id();
        }
    }
}

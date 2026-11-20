package dev.starryeye.organization.core.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 삭제 가드가 멈춘 회차가 지우려던 것의 내역 — 운영자가 "정말 지워도 되나"를 숫자와 예로 확인하게 한다(설계 2026-11-20 H2·H3).
 *
 * <p><b>가드가 걸렸을 때만 만든다.</b> 지울 줄 전체를 한 번 훑는 비용이 들기 때문이다. 줄은 관계({@code direct_member}·{@code child}·그 밖)로
 * 묶어 세고, 직원·조직 갈래는 접두사가 맞는 자리만 그 회차의 디렉터리 그림으로 가른다(설계 §3.1 표). 모양이 다른 줄도 예외 없이 센다.
 *
 * @param usersGone 그림에 없는 직원
 * @param usersInactive 그림에 있고 비활성인 직원
 * @param usersLosingMembership 그림에 있고 활성인데 소속 줄이 지워지는 직원(이동자가 든다)
 * @param groupsGone 그림에 없는 조직
 * @param groupsLosingParent 그림에 있는데 상위 연결이 지워지는 조직
 */
public record GuardHold(
        int directMemberLines,
        int childLines,
        int otherLines,
        Sample usersGone,
        Sample usersInactive,
        Sample usersLosingMembership,
        Sample groupsGone,
        Sample groupsLosingParent
) {

    /** 갈래마다 싣는 예의 수 — 사전순 앞에서부터. */
    public static final int SAMPLE_SIZE = 10;

    /** 한 갈래: 서로 다른 id 의 수와 사전순 앞 {@link #SAMPLE_SIZE} 개. */
    public record Sample(int count, List<String> ids) {

        public Sample {
            ids = List.copyOf(ids);
        }

        static Sample of(Set<String> distinct) {
            return new Sample(distinct.size(), distinct.stream().sorted().limit(SAMPLE_SIZE).toList());
        }
    }

    /** 지울 줄의 수 — 세 줄 수의 합이며 가드 메시지의 "삭제 대상 N건"과 같다. */
    public int totalLines() {
        return directMemberLines + childLines + otherLines;
    }

    public static GuardHold of(Set<RelationTuple> deletes, DirectorySnapshot directory) {
        String userPrefix = RelationTuple.USER_TYPE + ":";
        String groupPrefix = RelationTuple.GROUP_TYPE + ":";
        int directMember = 0;
        int child = 0;
        int other = 0;
        Set<String> usersGone = new HashSet<>();
        Set<String> usersInactive = new HashSet<>();
        Set<String> usersLosing = new HashSet<>();
        Set<String> groupsGone = new HashSet<>();
        Set<String> groupsLosingParent = new HashSet<>();

        for (RelationTuple t : deletes) {
            boolean isDirectMember = RelationTuple.DIRECT_MEMBER.equals(t.relation());
            boolean isChild = RelationTuple.CHILD.equals(t.relation());
            if (isDirectMember) {
                directMember++;
            } else if (isChild) {
                child++;
            } else {
                other++;
                continue;
            }
            if (isDirectMember && t.user().startsWith(userPrefix)) {
                String id = t.user().substring(userPrefix.length());
                DirectoryUser user = directory.users().get(id);
                if (user == null) {
                    usersGone.add(id);
                } else if (!user.active()) {
                    usersInactive.add(id);
                } else {
                    usersLosing.add(id);
                }
            }
            if (isChild && t.user().startsWith(groupPrefix)) {
                String id = t.user().substring(groupPrefix.length());
                (directory.groups().containsKey(id) ? groupsLosingParent : groupsGone).add(id);
            }
            if (t.object().startsWith(groupPrefix)) {
                String id = t.object().substring(groupPrefix.length());
                if (!directory.groups().containsKey(id)) {
                    groupsGone.add(id);
                }
            }
        }
        return new GuardHold(directMember, child, other,
                Sample.of(usersGone), Sample.of(usersInactive), Sample.of(usersLosing),
                Sample.of(groupsGone), Sample.of(groupsLosingParent));
    }

    /** 로그·메시지용 한 줄 — 수만 담고 id 는 싣지 않는다. */
    public String summary() {
        return "지울 줄 %d건(소속 %d·하위 조직 연결 %d·그 밖 %d) — 직원 사라짐 %d·비활성 %d·소속 잃음 %d / 조직 사라짐 %d·상위 잃음 %d"
                .formatted(totalLines(), directMemberLines, childLines, otherLines,
                        usersGone.count(), usersInactive.count(), usersLosingMembership.count(),
                        groupsGone.count(), groupsLosingParent.count());
    }
}

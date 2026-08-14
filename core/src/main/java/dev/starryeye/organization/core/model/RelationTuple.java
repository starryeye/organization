package dev.starryeye.organization.core.model;

public record RelationTuple(String user, String relation, String object) {

    public static final String DIRECT_MEMBER = "direct_member";
    public static final String CHILD = "child";

    public static final String USER_TYPE = "user";
    public static final String GROUP_TYPE = "group";

    /** 그룹 G 에 직원 U 가 직접 속한다: (user:U, direct_member, group:G) */
    public static RelationTuple directMember(String userId, String groupId) {
        return new RelationTuple(USER_TYPE + ":" + userId, DIRECT_MEMBER, GROUP_TYPE + ":" + groupId);
    }

    /** 그룹 C 가 그룹 P 의 하위 조직이다: (group:C, child, group:P) */
    public static RelationTuple child(String childGroupId, String parentGroupId) {
        return new RelationTuple(GROUP_TYPE + ":" + childGroupId, CHILD, GROUP_TYPE + ":" + parentGroupId);
    }
}

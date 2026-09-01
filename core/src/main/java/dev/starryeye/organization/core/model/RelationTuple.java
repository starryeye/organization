package dev.starryeye.organization.core.model;

public record RelationTuple(String user, String relation, String object) {

    public static final String DIRECT_MEMBER = "direct_member";
    public static final String CHILD = "child";
    public static final String MEMBER = "member";

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

    /**
     * 롤업까지 한 번에 묻는 질의용 튜플: (user:U, member, group:G)
     *
     * <p>{@link #directMember} 와 달리 <b>쓰지 않고 묻기만 하는</b> 튜플이다. 인가 모델에서
     * {@code member} 는 {@code direct_member or member from child} 로 정의돼 있어, 직속이든
     * 상위 조직이든 한 번의 Check 로 답이 나온다. {@code direct_member} 로 물으면 조상 조직에
     * 대해서는 항상 false 라 롤업 경로가 전부 드리프트로 보인다.
     *
     * <p>이 팩토리가 없어서 조회 경로가 {@code "user:"}/{@code "group:"} 접두사를 직접 이어
     * 붙이고 있었다. 접두사 규칙은 이 클래스 것이다 — {@code Keys} 에 키 규칙을 한 곳에 모으는
     * 것과 같은 이유다.
     */
    public static RelationTuple member(String userId, String groupId) {
        return new RelationTuple(USER_TYPE + ":" + userId, MEMBER, GROUP_TYPE + ":" + groupId);
    }

    /** 튜플의 한 자리에 들어가는 직원 참조: {@code user:U} */
    public static String userRef(String userId) {
        return USER_TYPE + ":" + userId;
    }

    /** 튜플의 한 자리에 들어가는 조직 참조: {@code group:G} */
    public static String groupRef(String groupId) {
        return GROUP_TYPE + ":" + groupId;
    }

    /**
     * 이 튜플이 <b>어느 쪽 자리로든</b> 그 엔티티를 언급하는가.
     *
     * <p>양쪽을 다 보는 것이 핵심이다. 조직 {@code DEV001} 을 건드리는 연산의 후보에는
     * {@code dm(m,DEV001)}·{@code child(m,DEV001)} 처럼 DEV001 이 object 인 것뿐 아니라
     * 상위 조직으로의 {@code child(DEV001,parent)} — DEV001 이 user 자리인 것 — 도 들어가야
     * 한다 (설계 §5.2 표).
     */
    public boolean mentions(String typedId) {
        return user.equals(typedId) || object.equals(typedId);
    }
}

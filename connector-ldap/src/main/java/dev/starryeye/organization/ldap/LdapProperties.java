package dev.starryeye.organization.ldap;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("ldap")
public class LdapProperties {

    private String url = "ldap://localhost:1389";
    private String baseDn = "dc=example,dc=com";
    private String bindDn;
    private String bindPassword;
    private int pageSize = 500;

    /**
     * LDAP 읽기 실패 시 재시도 횟수 (설계 §9). OpenFGA 어댑터와 같은 이름·같은 기본값을 쓴다.
     *
     * <p>재시도가 걸리는 것은 <b>예외</b>뿐이다. LDAP 이 성공적으로 잘못된 답을 주는 경우
     * (필터 오류로 0건을 반환하는 것 같은)는 정상 응답이라 여기 걸리지 않는다 — 그건
     * 삭제 가드가 잡을 일이고, 이 재시도가 그 경계를 흐리지 않는다.
     */
    private int maxRetries = 3;

    /** group-of-names | dit */
    private String strategy = "group-of-names";

    private GroupOfNames groupOfNames = new GroupOfNames();
    private Dit dit = new Dit();

    @Getter
    @Setter
    public static class GroupOfNames {
        private String userSearchBase = "ou=people";
        private String userObjectClass = "inetOrgPerson";
        /** 직원 아이디. employeeNumber 등으로 교체 가능 */
        private String userIdAttribute = "uid";
        private String userNameAttribute = "displayName";
        private String userMailAttribute = "mail";
        private String groupSearchBase = "ou=groups";
        private String groupObjectClass = "groupOfNames";
        /** 조직코드 */
        private String groupIdAttribute = "cn";
        /** 조직명. LDAP 그룹에는 표시명 표준 속성이 없어 description 을 쓴다 */
        private String groupNameAttribute = "description";
        private String memberAttribute = "member";
    }

    @Getter
    @Setter
    public static class Dit {
        private String rootDn = "ou=company";
        private String orgUnitObjectClass = "organizationalUnit";
        /** 조직코드 */
        private String groupIdAttribute = "ou";
        /** 조직명. 없으면 조직코드로 대체 */
        private String groupNameAttribute = "description";
        private String userObjectClass = "inetOrgPerson";
        private String userIdAttribute = "uid";
        private String userNameAttribute = "displayName";
        private String userMailAttribute = "mail";
    }
}

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

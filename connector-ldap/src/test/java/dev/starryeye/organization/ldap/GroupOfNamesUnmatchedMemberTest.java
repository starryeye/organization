package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.MemberMatchingFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 부분 불일치는 정상이다 — 연락처나 컴퓨터 계정처럼 우리가 읽지 않는 엔트리가 그룹에
 * 섞여 있을 수 있고, 그것은 {@code GroupOfNamesStrategyTest} 가 이미 덮는다.
 *
 * <p>여기서 보는 것은 <b>전부</b> 어긋난 경우다. 그때는 값이 이상한 것이 아니라 DN 형태
 * 자체가 어긋난 것이고, 그대로 진행하면 아무도 권한을 받지 못한 채 동기화가 성공으로 끝난다.
 */
class GroupOfNamesUnmatchedMemberTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=people,dc=example,dc=com
                objectClass: organizationalUnit
                ou: people

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: uid=kim,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                cn: Kim Chulsoo
                sn: Kim
                displayName: 김철수

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                member: CN=Kim Chulsoo,OU=Seoul,OU=Users,DC=corp,DC=example,DC=com
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=people");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setUserNameAttribute("displayName");
        g.setUserMailAttribute("mail");
        g.setGroupSearchBase("ou=groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }

    @Test
    @DisplayName("DN 형태가 어긋나 멤버가 하나도 대조되지 않으면 그 회차를 실패시킨다")
    void 전부_대조되지_않으면_실패시킨다() {
        // given — 사용자는 읽히지만 member 값은 전혀 다른 트리를 가리킨다
        var strategy = new GroupOfNamesStrategy(설정());

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .as("조용히 멤버 0명으로 적재하면 아무도 권한을 받지 못한 채 성공으로 끝난다")
                .isInstanceOf(MemberMatchingFailedException.class)
                .hasMessageContaining("검색 베이스");
    }
}

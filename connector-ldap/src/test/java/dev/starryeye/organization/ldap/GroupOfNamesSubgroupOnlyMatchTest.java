package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.MemberMatchingFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H 의 결함은 처음부터 <b>사용자 쪽</b>만의 것이었다 — 그룹 매퍼는 결함이 있던 적이 없다.
 * 그래서 그룹이 검색 베이스 바로 아래 평평하게 있고 사용자만 조직 OU 아래 깊이 있는(흔한)
 * 트리에서는, 그룹의 {@code member} 중 하위 조직을 가리키는 값은 전부 대조되고 사람을
 * 가리키는 값만 전부 어긋날 수 있다. 사람과 그룹 대조를 하나로 합쳐 세면(대조된 것이
 * 하나라도 있으면 통과) 이 상황에서 {@code UnmatchedMemberGuard} 가 조용히 넘어간다 — 모든
 * 그룹이 하위 조직만 안은 채 사람은 한 명도 없이 적재되고 동기화는 성공으로 끝난다.
 *
 * <p>여기서 보는 것이 바로 그 모양이다: {@code DEV001} 의 member 는 진짜 하위 조직
 * {@code DEV002}(대조됨)와, 어디에도 없는 형태의 사람 DN(대조 안 됨) 하나씩을 가리킨다.
 */
class GroupOfNamesSubgroupOnlyMatchTest extends EmbeddedLdapSupport {

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

                dn: cn=DEV002,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV002
                description: 백엔드팀
                member: cn=nobody,ou=people,dc=example,dc=com

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                member: cn=DEV002,ou=groups,dc=example,dc=com
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
    @DisplayName("하위 조직만 대조되고 사람은 하나도 대조되지 않으면 그 회차를 실패시킨다")
    void 하위조직만_대조되면_실패시킨다() {
        // given — DEV001 의 member 는 진짜 하위 조직(DEV002, 대조됨)과 어디에도 없는 형태의
        // 사람 DN(대조 안 됨) 을 하나씩 가리킨다. 대조된 것이 하나(그룹)라도 있으니, 사람·그룹을
        // 합쳐서 세는 예전 로직이라면 이 상황에서도 가드가 조용히 지나간다.
        var strategy = new GroupOfNamesStrategy(설정());

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .as("하위 조직만 채워지고 사람은 한 명도 없이 적재되면 아무도 권한을 받지 못한 채"
                        + " 성공으로 끝난다")
                .isInstanceOf(MemberMatchingFailedException.class)
                .hasMessageContaining("검색 베이스");
    }
}

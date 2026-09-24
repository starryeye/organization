package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Active Directory 의 모양 — 사용자가 검색 베이스 <b>바로 아래가 아니라</b> 조직 OU
 * 아래에 있고, RDN 이 식별 속성({@code uid})이 아니라 {@code cn} 이다.
 *
 * <p>다른 테스트들은 모두 평면 트리라, DN 을 조립해 대조하던 예전 코드가 우연히 맞아
 * 떨어졌다. 이 결함(2026-09-11 코드리뷰 H)은 깊은 트리를 읽어야만 드러난다.
 */
class GroupOfNamesDeepTreeTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=users,dc=example,dc=com
                objectClass: organizationalUnit
                ou: users

                dn: ou=Seoul,ou=users,dc=example,dc=com
                objectClass: organizationalUnit
                ou: Seoul

                dn: cn=Hong Gildong,ou=Seoul,ou=users,dc=example,dc=com
                objectClass: inetOrgPerson
                cn: Hong Gildong
                sn: Hong
                uid: hgd
                displayName: 홍길동
                mail: hgd@example.com

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: cn=dev,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: dev
                description: 개발팀
                member: CN=Hong Gildong,OU=Seoul,OU=users,DC=example,DC=com
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=users");
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
    @DisplayName("사용자가 검색 베이스 바로 아래가 아니어도 그룹 멤버로 대조된다")
    void 깊은_트리의_사용자가_멤버로_대조된다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("hgd");
        assertThat(snapshot.groups().get("dev").members())
                .as("member 값은 서버가 준 DN 이다 — 우리가 조립한 DN 으로 대조하면 0명이 된다")
                .containsExactly(MemberRef.user("hgd"));
    }

    @Test
    @DisplayName("externalId 는 서버가 준 DN 이다 — 식별 속성으로 조립한 DN 이 아니다")
    void externalId가_서버가_준_DN이다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users().get("hgd").externalId())
                .containsIgnoringCase("cn=Hong Gildong")
                .containsIgnoringCase("ou=Seoul")
                .containsIgnoringCase(BASE_DN)
                .as("uid 로 조립한 DN 은 이 디렉터리에 존재하지 않는 엔트리를 가리킨다")
                .doesNotContainIgnoringCase("uid=hgd");
    }
}

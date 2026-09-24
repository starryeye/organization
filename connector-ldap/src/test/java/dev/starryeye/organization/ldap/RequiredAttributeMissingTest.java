package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 직원 아이디 속성이 없는 엔트리. 두 전략 모두 읽기를 실패시키고, 그 실패는 <b>다시 읽어도 같은 결과</b>인
 * 종류라 재시도하지 않는다 — 같은 디렉터리를 몇 번 더 읽어도 그 속성은 생기지 않는다.
 */
class RequiredAttributeMissingTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: cn=nobody,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                cn: nobody
                sn: nobody

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: cn=nobody,ou=company,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("groupOfNames — 직원 아이디 속성이 없으면 재시도하지 않는 종류로 실패한다")
    void groupOfNames는_데이터_오류로_실패한다() {
        // given
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=company");
        g.setGroupSearchBase("ou=groups");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("uid");
    }

    @Test
    @DisplayName("DIT — 직원 아이디 속성이 없으면 재시도하지 않는 종류로 실패한다")
    void dit는_데이터_오류로_실패한다() {
        // given
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        properties.getDit().setRootDn("ou=company");

        // when, then
        assertThatThrownBy(() -> new DitStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("uid")
                .hasMessageContaining("cn=nobody");
    }
}

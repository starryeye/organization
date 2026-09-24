package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계정 상태 속성의 값이 정수가 아니면 두 전략 모두 읽기를 실패시킨다 — 짐작해 읽으면 막힌 퇴사자를 활성으로
 * 두거나 멀쩡한 직원의 권한을 지운다. 예외는 그 회차의 동기화를 실패시킨다.
 */
class AccountStatusInvalidValueTest extends EmbeddedLdapSupport {

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

                dn: uid=odd,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: odd
                cn: odd
                sn: odd
                userAccountControl: abc

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: uid=odd,ou=company,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("groupOfNames — 계정 상태 값이 정수가 아니면 읽기가 실패한다")
    void groupOfNames는_실패한다() {
        // given
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=company");
        g.setGroupSearchBase("ou=groups");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .hasStackTraceContaining("userAccountControl");
    }

    @Test
    @DisplayName("DIT — 계정 상태 값이 정수가 아니면 읽기가 실패한다")
    void dit는_실패한다() {
        // given
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        properties.getDit().setRootDn("ou=company");

        // when, then
        assertThatThrownBy(() -> new DitStrategy(properties).read(ldapTemplate))
                .hasStackTraceContaining("userAccountControl");
    }
}

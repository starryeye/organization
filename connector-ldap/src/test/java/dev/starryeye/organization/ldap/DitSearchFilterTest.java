package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIT 전략도 직원 검색 필터 전체를 설정으로 받는다(점검 M12). 컴퓨터 계정은 {@code user} 의 하위 클래스라
 * {@code (objectClass=user)} 만으로는 걸러지지 않는다 — {@link SearchFilterTest} 와 같은 이유다.
 */
class DitSearchFilterTest extends EmbeddedLdapSupport {

    private static final String AD_사람 = "(&(objectCategory=person)(objectClass=user))";

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: domain
                dc: example

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company

                dn: ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001

                dn: uid=kim,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: top
                objectClass: person
                objectClass: user
                objectCategory: person
                uid: kim
                cn: kim

                dn: uid=DEV-PC01$,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: top
                objectClass: person
                objectClass: user
                objectClass: computer
                objectCategory: computer
                uid: DEV-PC01$
                cn: DEV-PC01
                """;
    }

    @Test
    @DisplayName("AD 의 사람 필터를 쓰면 DIT 에서도 컴퓨터 계정은 직원도 멤버도 되지 않는다(점검 M12)")
    void AD_사람_필터는_컴퓨터를_뺀다() {
        // given
        var properties = 이름기반();
        properties.setStrategy("dit");
        properties.getDit().setRootDn("ou=company");
        properties.getDit().setUserFilter(AD_사람);

        // when
        var snapshot = new DitStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("kim");
        assertThat(snapshot.groups().get("DEV001").members()).containsExactly(MemberRef.user("kim"));
    }
}

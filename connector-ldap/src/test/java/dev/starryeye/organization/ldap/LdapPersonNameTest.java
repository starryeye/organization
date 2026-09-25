package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** LDAP 표준 이름 속성을 직원 이름으로 읽는다(S-3 설계 §5). 속성 이름은 설정으로 빼지 않는다. */
class LdapPersonNameTest extends EmbeddedLdapSupport {

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

                dn: uid=hong,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: hong
                cn: 홍길동
                sn: 홍
                givenName: 길동
                middleName: 철
                generationQualifier: Jr.

                dn: uid=plain,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: plain
                cn: plain
                sn: plain

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: uid=hong,ou=people,dc=example,dc=com
                member: uid=plain,ou=people,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("groupOfNames 전략이 givenName·sn·middleName·generationQualifier 를 이름으로 읽는다")
    void groupOfNames_가_이름을_읽는다() {
        // given — GroupOfNamesAccountStatusTest 와 같은 설정으로 전략을 만든다
        var properties = new LdapProperties();
        properties.setBaseDn("dc=example,dc=com");
        properties.getGroupOfNames().setUserSearchBase("ou=people");
        properties.getGroupOfNames().setGroupSearchBase("ou=groups");

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users().get("hong").name())
                .isEqualTo(new PersonName(null, "홍", "길동", "철", null, "Jr."));
        assertThat(snapshot.users().get("plain").name())
                .isEqualTo(new PersonName(null, "plain", null, null, null, null));
    }
}

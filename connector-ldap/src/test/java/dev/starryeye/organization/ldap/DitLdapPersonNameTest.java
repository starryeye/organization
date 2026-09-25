package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIT 전략도 groupOfNames 전략과 같은 LDAP 표준 이름 속성을 직원 이름으로 읽는다(S-3 설계 §5).
 * {@link DitAccountStatusTest} 와 같은 방식으로 전략을 구성한다 — 직원 엔트리는 {@code ou} 트리
 * 바로 아래에 둔다.
 */
class DitLdapPersonNameTest extends EmbeddedLdapSupport {

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

                dn: uid=hong,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: hong
                cn: 홍길동
                sn: 홍
                givenName: 길동
                middleName: 철
                generationQualifier: Jr.

                dn: uid=plain,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: plain
                cn: plain
                sn: plain
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        var d = properties.getDit();
        d.setRootDn("ou=company");
        d.setOrgUnitObjectClass("organizationalUnit");
        d.setGroupIdAttribute("ou");
        d.setUserObjectClass("inetOrgPerson");
        d.setUserIdAttribute("uid");
        return properties;
    }

    @Test
    @DisplayName("DIT 전략이 givenName·sn·middleName·generationQualifier 를 이름으로 읽는다")
    void dit_가_이름을_읽는다() {
        // given
        var strategy = new DitStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users().get("hong").name())
                .isEqualTo(new PersonName(null, "홍", "길동", "철", null, "Jr."));
        assertThat(snapshot.users().get("plain").name())
                .isEqualTo(new PersonName(null, "plain", null, null, null, null));
    }
}

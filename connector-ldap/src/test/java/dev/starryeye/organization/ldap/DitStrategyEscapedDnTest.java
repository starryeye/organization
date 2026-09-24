package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDN 값에 <b>이스케이프된 쉼표</b>가 든 디렉터리. DIT 전략은 dn 경로로 부모를 찾으므로,
 * 첫 쉼표에서 자르면 {@code ou=R\,D} 의 쉼표를 RDN 경계로 오인해 부모를 잃는다 —
 * 그 아래 전원이 소속을 <b>조용히</b> 잃는다. groupOfNames 전략에서 고친 것(2026-09-24)과
 * 같은 종류의 결함이다.
 */
class DitStrategyEscapedDnTest extends EmbeddedLdapSupport {

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

                dn: ou=R\\,D,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: R,D
                description: 연구개발

                dn: cn=Lee\\, Minho,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                cn: Lee, Minho
                sn: Lee
                uid: lee
                displayName: 이민호
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
        d.setGroupNameAttribute("description");
        d.setUserObjectClass("inetOrgPerson");
        d.setUserIdAttribute("uid");
        d.setUserNameAttribute("displayName");
        d.setUserMailAttribute("mail");
        return properties;
    }

    @Test
    @DisplayName("이름에 쉼표가 든 OU 도 부모를 찾아 하위 조직으로 이어진다")
    void 쉼표가_든_OU가_부모에_이어진다() {
        // given
        var strategy = new DitStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — "R,D" 는 IdNormalizer 가 쉼표를 밑줄로 바꿔 "R_D" 가 된다
        assertThat(snapshot.groups().get("company").members())
                .as("첫 쉼표에서 자르면 부모를 'D,ou=company' 로 오인한다")
                .contains(MemberRef.group("R_D"));
    }

    @Test
    @DisplayName("RDN 에 쉼표가 든 직원도 부모 조직의 멤버가 된다")
    void 쉼표가_든_직원이_소속된다() {
        // given
        var strategy = new DitStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("company").members())
                .as("첫 쉼표에서 자르면 부모를 ' Minho,ou=company' 로 오인한다")
                .contains(MemberRef.user("lee"));
    }
}

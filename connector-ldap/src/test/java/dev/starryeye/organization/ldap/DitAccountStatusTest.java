package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIT 전략도 AD 가 막은 계정을 비활성으로 읽는다. 두 전략은 같은 디렉터리를 같은 스냅샷으로 읽기로 돼 있어
 * 규칙이 한쪽에만 있으면 안 된다.
 */
class DitAccountStatusTest extends EmbeddedLdapSupport {

    private static final Clock 고정시계 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

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

                dn: uid=normal,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: normal
                cn: normal
                sn: normal
                userAccountControl: 512

                dn: uid=disabled,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: disabled
                cn: disabled
                sn: disabled
                userAccountControl: 66050

                dn: uid=expired,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: expired
                cn: expired
                sn: expired
                accountExpires: 132223104000000000
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
    @DisplayName("비활성화됐거나 만료된 직원은 비활성으로 읽히고, 소속은 그대로다")
    void 막힌_직원은_비활성이고_소속은_남는다() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users().get("disabled").active()).as("66050 = 비활성 + 암호 만료 없음").isFalse();
        assertThat(snapshot.users().get("expired").active()).as("accountExpires 가 2020-01-01").isFalse();
        assertThat(snapshot.users().get("normal").active()).isTrue();
        assertThat(snapshot.groups().get("company").members())
                .contains(MemberRef.user("disabled"), MemberRef.user("expired"));
    }
}

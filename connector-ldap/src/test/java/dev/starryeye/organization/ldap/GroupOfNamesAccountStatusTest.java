package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AD 가 막은 계정은 비활성으로 읽힌다 — 멤버십은 남고 권한 튜플만 사라진다(스펙 §2).
 * 지금 이 전략은 모든 직원을 활성으로 만들어, 막힌 퇴사자가 권한을 유지한다.
 */
class GroupOfNamesAccountStatusTest extends EmbeddedLdapSupport {

    /** 2026-01-01T00:00:00Z. 아래 만료일(2020-01-01)은 그보다 과거다. */
    private static final Clock 고정시계 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

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

                dn: uid=normal,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: normal
                cn: normal
                sn: normal
                userAccountControl: 512

                dn: uid=disabled,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: disabled
                cn: disabled
                sn: disabled
                userAccountControl: 514

                dn: uid=expired,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: expired
                cn: expired
                sn: expired
                userAccountControl: 512
                accountExpires: 132223104000000000

                dn: uid=plain,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: plain
                cn: plain
                sn: plain

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: uid=normal,ou=people,dc=example,dc=com
                member: uid=disabled,ou=people,dc=example,dc=com
                member: uid=expired,ou=people,dc=example,dc=com
                member: uid=plain,ou=people,dc=example,dc=com
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=people");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setGroupSearchBase("ou=groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setMemberAttribute("member");
        return properties;
    }

    @Test
    @DisplayName("비활성화됐거나 만료된 직원은 비활성으로 읽힌다")
    void 막힌_직원은_비활성이다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정(), 고정시계);

        // when
        var users = strategy.read(ldapTemplate).users();

        // then
        assertThat(users.get("disabled").active()).as("userAccountControl 514").isFalse();
        assertThat(users.get("expired").active()).as("accountExpires 가 2020-01-01").isFalse();
        assertThat(users.get("normal").active()).as("userAccountControl 512").isTrue();
        assertThat(users.get("plain").active()).as("두 속성이 없는 직원").isTrue();
    }

    @Test
    @DisplayName("막힌 직원도 그룹 멤버로는 남는다 — 멤버십은 두고 권한만 사라지는 것이 비활성이다")
    void 막힌_직원도_멤버로_남는다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정(), 고정시계);

        // when
        var members = strategy.read(ldapTemplate).groups().get("DEV").members();

        // then
        assertThat(members).contains(MemberRef.user("disabled"), MemberRef.user("expired"));
    }
}

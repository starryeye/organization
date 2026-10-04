package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.InvalidSearchFilterException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검색 필터는 objectClass 하나가 아니라 필터 전체(RFC 4515)를 설정으로 받는다(점검 M12).
 *
 * <p>AD 에서 {@code user} 는 {@code computer} 의 상위 클래스라 {@code (objectClass=user)} 만 쓰면 컴퓨터 계정까지
 * 직원이 된다. 임베디드 서버는 스키마가 없어 AD 의 클래스·속성을 그대로 둘 수 있다. 실제 AD 의
 * {@code objectCategory} 값은 스키마 DN 이지만 AD 는 {@code objectCategory=person} 을 그 DN 으로 풀어 준다 —
 * 여기서는 값 {@code person} 을 그대로 둔다.
 */
class SearchFilterTest extends EmbeddedLdapSupport {

    private static final String AD_사람 = "(&(objectCategory=person)(objectClass=user))";

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: domain
                dc: example

                dn: ou=people,dc=example,dc=com
                objectClass: organizationalUnit
                ou: people

                dn: uid=kim,ou=people,dc=example,dc=com
                objectClass: top
                objectClass: person
                objectClass: user
                objectCategory: person
                uid: kim
                cn: kim

                dn: uid=DEV-PC01$,ou=people,dc=example,dc=com
                objectClass: top
                objectClass: person
                objectClass: user
                objectClass: computer
                objectCategory: computer
                uid: DEV-PC01$
                cn: DEV-PC01

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: group
                cn: DEV
                member: uid=kim,ou=people,dc=example,dc=com
                member: uid=DEV-PC01$,ou=people,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("AD 의 사람 필터를 쓰면 user 의 하위 클래스인 컴퓨터 계정은 직원이 되지 않는다(점검 M12)")
    void AD_사람_필터는_컴퓨터를_뺀다() {
        // given
        var properties = 이름기반();
        properties.getGroupOfNames().setUserFilter(AD_사람);
        properties.getGroupOfNames().setGroupFilter("(objectClass=group)");

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("kim");
        assertThat(snapshot.groups().get("DEV").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("objectClass=user 하나만 쓰면 컴퓨터 계정까지 직원이 된다 — 필터 전체를 받아야 하는 이유")
    void user_클래스만이면_컴퓨터도_잡힌다() {
        // given
        var properties = 이름기반();
        properties.getGroupOfNames().setUserFilter("(objectClass=user)");
        properties.getGroupOfNames().setGroupFilter("(objectClass=group)");

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("kim", "DEV-PC01$");
    }

    @Test
    @DisplayName("문법이 틀린 필터는 서버에 보내기 전에 거절된다")
    void 문법이_틀린_필터는_거절된다() {
        // given
        var properties = 이름기반();
        properties.getGroupOfNames().setUserFilter("(objectClass=inetOrgPerson");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isInstanceOf(InvalidSearchFilterException.class);
    }
}

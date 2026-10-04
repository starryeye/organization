package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.fixture.ActiveDirectorySids;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AD 기본 그룹(점검 M10). AD 는 사용자의 기본 그룹 소속을 그 그룹의 {@code member} 에 적지 않고 사용자의 {@code primaryGroupID}(그룹의 RID)로만 표현한다.
 * 그룹의 RID 는 이진 {@code objectSid} 의 마지막 하위 권한이다. 이진으로 받으려면 컨텍스트 소스가 {@link LdapConfig} 의 JNDI 환경을 타야 해서,
 * 이 테스트는 {@link #LdapConfig로_만든_템플릿} 으로 읽는다.
 *
 * <p>그룹 {@code DEV} 의 RID 는 1105 다. {@code kim} 은 기본 그룹으로만, {@code lee} 는 {@code member} 로만(기본 그룹은 읽지 않은 Domain Users 513),
 * {@code park} 은 둘 다로 속한다.
 */
class PrimaryGroupTest extends EmbeddedLdapSupport {

    private static String objectSid(long rid) {
        return Base64.getEncoder().encodeToString(ActiveDirectorySids.도메인_RID(rid));
    }

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

                dn: uid=kim,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                primaryGroupID: 1105

                dn: uid=lee,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: lee
                primaryGroupID: 513

                dn: uid=park,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: park
                primaryGroupID: 1105

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                objectSid:: %s
                member: uid=lee,ou=people,dc=example,dc=com
                member: uid=park,ou=people,dc=example,dc=com
                """.formatted(objectSid(1105));
    }

    @Test
    @DisplayName("primaryGroupID 가 그룹 objectSid 의 RID 와 같으면 그 그룹의 멤버다 — AD 는 기본 그룹 소속을 member 에 적지 않는다(점검 M10)")
    void 기본_그룹은_멤버다() throws Exception {
        // given
        var properties = 이름기반();
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(LdapConfig로_만든_템플릿(properties));

        // then — kim 은 기본 그룹으로, lee 는 member 로, park 은 둘 다(한 번만)
        assertThat(snapshot.groups().get("DEV").members()).containsExactlyInAnyOrder(
                MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.user("park"));
    }

    @Test
    @DisplayName("읽지 않은 그룹을 가리키는 primaryGroupID(Domain Users 513)는 아무 소속도 더하지 않는다")
    void 읽지_않은_기본_그룹은_무시한다() throws Exception {
        // given
        var properties = 이름기반();
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(LdapConfig로_만든_템플릿(properties));

        // then
        assertThat(snapshot.groups()).containsOnlyKeys("DEV");
        assertThat(snapshot.users().get("lee").active()).isTrue();
    }

    @Test
    @DisplayName("objectSid 가 이진으로 오지 않으면(이진 선언 없는 컨텍스트 소스) 데이터 오류다 — 선언이 실제로 필요하다")
    void 이진_선언이_없으면_데이터_오류다() {
        // given — EmbeddedLdapSupport 의 기본 템플릿은 LdapConfig 의 JNDI 환경을 타지 않는다
        var strategy = new GroupOfNamesStrategy(이름기반());

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("objectSid")
                .hasMessageContaining("cn=DEV");
    }

    @Test
    @DisplayName("같은 RID 를 가진 그룹이 둘이면 데이터 오류다 — 한 도메인에서 RID 는 유일하다")
    void 같은_RID_의_그룹이_둘이면_데이터_오류다() throws Exception {
        // given — DEV 와 같은 objectSid 를 가진 두 번째 그룹
        server.add("dn: cn=OPS,ou=groups," + BASE_DN, "objectClass: groupOfNames", "cn: OPS",
                "objectSid:: " + objectSid(1105), "member: uid=lee,ou=people," + BASE_DN);
        var properties = 이름기반();
        var strategy = new GroupOfNamesStrategy(properties);
        var template = LdapConfig로_만든_템플릿(properties);

        // when, then — 속성 이름과 두 그룹의 id·DN 을 모두 댄다. 운영자가 어느 엔트리를 고칠지 알아야 한다
        assertThatThrownBy(() -> strategy.read(template))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("objectSid")
                .hasMessageContaining("1105")
                .hasMessageContaining("'DEV'")
                .hasMessageContaining("'OPS'")
                .hasMessageContaining("cn=DEV,ou=groups," + BASE_DN)
                .hasMessageContaining("cn=OPS,ou=groups," + BASE_DN);
    }

    @Test
    @DisplayName("식별 속성이 없어 건너뛰는 직원의 primaryGroupID 는 읽지 않는다 — 정수가 아니어도 회차가 멈추지 않는다")
    void 건너뛰는_직원의_기본_그룹은_읽지_않는다() throws Exception {
        // given — uid 가 없는 직원이 표준 밖의 primaryGroupID 를 가졌다
        server.add("dn: cn=ghost,ou=people," + BASE_DN, "objectClass: inetOrgPerson", "cn: ghost",
                "primaryGroupID: abc");
        var properties = 이름기반();
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(LdapConfig로_만든_템플릿(properties));

        // then — 그 직원은 빠지고 나머지는 그대로 읽힌다
        assertThat(snapshot.users()).containsOnlyKeys("kim", "lee", "park");
    }

    @Test
    @DisplayName("식별 속성이 없어 건너뛰는 그룹의 objectSid 는 읽지 않는다 — SID 형식이 아니어도 회차가 멈추지 않는다")
    void 건너뛰는_그룹의_objectSid_는_읽지_않는다() throws Exception {
        // given — cn 이 없는 그룹이 SID 가 아닌 objectSid 를 가졌다
        server.add("dn: ou=nocn,ou=groups," + BASE_DN, "objectClass: groupOfNames", "ou: nocn",
                "objectSid:: " + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), "member: uid=lee,ou=people," + BASE_DN);
        var properties = 이름기반();
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(LdapConfig로_만든_템플릿(properties));

        // then
        assertThat(snapshot.groups()).containsOnlyKeys("DEV");
    }
}

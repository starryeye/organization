package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표시명 폴백이 <b>정규화된 식별자가 아니라 원본 값</b>을 쓰는지 확인한다.
 *
 * <p>조직명·직원명 속성이 비어 있으면 두 전략 모두 식별자로 폴백한다. 그런데 그 식별자는
 * {@code IdNormalizer} 를 거쳐 금지 문자가 밑줄로 바뀐 값이다. 그대로 표시명에 넣으면
 * <b>사람이 읽는 칸에 밑줄이 새어 나온다</b> — 관리자 조회 API 가 생긴 지금은 그게 화면에
 * 그대로 보인다. 폴백은 원본을 써야 한다.
 *
 * <p>전용 픽스처를 쓰는 이유는 공유 LDIF 에 조직을 하나 더하면 그 조직도를 정확 집합으로
 * 단언하는 기존 테스트들이 함께 깨지기 때문이다({@code DitStrategyDuplicateCodeTest} 와 같은 이유).
 */
class DisplayNameFallbackTest extends EmbeddedLdapSupport {

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

                dn: ou=QA 001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: QA 001

                dn: uid=han gil,ou=QA 001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: han gil
                sn: han

                dn: ou=people,dc=example,dc=com
                objectClass: organizationalUnit
                ou: people

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: uid=lee,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: lee
                cn: Lee Younghee
                sn: Lee
                displayName: 이영희

                dn: cn=QA 001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: QA 001
                member: uid=lee,ou=people,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("groupOfNames — 조직명 속성이 없으면 정규화된 코드가 아니라 원본 cn 이 표시명이 된다")
    void groupOfNames_조직_표시명_폴백은_원본이다() {
        // given — cn 에 공백이 있고 description 이 없다
        var strategy = new GroupOfNamesStrategy(groupOfNames설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 코드는 정규화되지만 표시명은 원본 그대로다
        assertThat(snapshot.groups()).containsKey("QA_001");
        assertThat(snapshot.groups().get("QA_001").displayName()).isEqualTo("QA 001");
    }

    @Test
    @DisplayName("DIT — 조직명 속성이 없으면 정규화된 코드가 아니라 원본 ou 가 표시명이 된다")
    void DIT_조직_표시명_폴백은_원본이다() {
        // given — ou 에 공백이 있고 description 이 없다. 코드는 QA_001 로 정규화된다
        var strategy = new DitStrategy(dit설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups()).containsKey("QA_001");
        assertThat(snapshot.groups().get("QA_001").displayName()).isEqualTo("QA 001");
    }

    @Test
    @DisplayName("DIT — 직원명 속성이 없으면 정규화된 아이디가 아니라 원본 uid 가 표시명이 된다")
    void DIT_직원_표시명_폴백은_원본이다() {
        // given — uid 에 공백이 있고 displayName 도 cn 도 없다. 그래야 마지막 폴백까지 간다
        var strategy = new DitStrategy(dit설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsKey("han_gil");
        assertThat(snapshot.users().get("han_gil").displayName()).isEqualTo("han gil");
    }

    private LdapProperties dit설정() {
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

    private LdapProperties groupOfNames설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=people");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setUserNameAttribute("displayName");
        g.setUserMailAttribute("mail");
        g.setGroupSearchBase("ou=groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }
}

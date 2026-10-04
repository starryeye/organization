package dev.starryeye.organization.ldap;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DIT 전략은 엔트리를 읽는 자리(매퍼)에서 계정 상태까지 계산한다(P7 — 원본 엔트리를 회차 끝까지 쥐지 않으려고). 그래서 아이디가
 * 겹쳐 가드가 건너뛸 엔트리도 계정 상태 값이 정수가 아니면 읽기가 실패한다. 예전에는 건너뛴 뒤에야 계산해서 그 엔트리의 깨진 값이 드러나지
 * 않았다. 깨진 값이 어느 엔트리에 있든 같은 디렉터리를 다시 읽어도 같은 결과이므로, 건너뛸 엔트리라고 눈감지 않고 데이터 오류로 알린다.
 *
 * <p>겹치는 아이디는 이름 기반 id(uid)에서 서로 다른 부모 아래의 같은 {@code uid} 로 만든다 — DIT 은 형제 사이에서만 RDN 이 유일하다.
 * 어느 쪽이 먼저 읽혀 살아남고 어느 쪽이 건너뛸 엔트리가 되는지는 서버가 돌려주는 순서에 달렸으므로, 깨진 값을 두 엔트리에 하나씩
 * 두는 테스트 둘이 짝을 이룬다 — 순서가 어떻든 둘 중 하나는 건너뛸 엔트리에 깨진 값이 있는 경우다.
 */
class DitDuplicateIdAccountStatusTest extends EmbeddedLdapSupport {

    private static final String 개발본부의_dup = "uid=dup,ou=DEV001,ou=company," + BASE_DN;
    private static final String 운영본부의_dup = "uid=dup,ou=OPS001,ou=company," + BASE_DN;

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

                dn: ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001

                dn: ou=OPS001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: OPS001

                dn: uid=dup,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: dup
                cn: dup
                sn: dup
                userAccountControl: 512

                dn: uid=dup,ou=OPS001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: dup
                cn: dup
                sn: dup
                userAccountControl: 512
                """;
    }

    @Test
    @DisplayName("아이디가 겹친 두 직원 중 DEV001 쪽의 계정 상태 값이 정수가 아니면 읽기가 실패한다")
    void 개발본부_쪽의_깨진_계정_상태도_실패한다() throws LDAPException {
        // given — uid 가 같은 두 직원, DEV001 쪽 userAccountControl 이 정수가 아니다
        깨뜨린다(개발본부의_dup);
        var strategy = new DitStrategy(DIT_이름기반());

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("userAccountControl")
                .hasMessageContaining("uid=dup,ou=DEV001");
    }

    @Test
    @DisplayName("아이디가 겹친 두 직원 중 OPS001 쪽의 계정 상태 값이 정수가 아니면 읽기가 실패한다")
    void 운영본부_쪽의_깨진_계정_상태도_실패한다() throws LDAPException {
        // given — uid 가 같은 두 직원, OPS001 쪽 userAccountControl 이 정수가 아니다
        깨뜨린다(운영본부의_dup);
        var strategy = new DitStrategy(DIT_이름기반());

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("userAccountControl")
                .hasMessageContaining("uid=dup,ou=OPS001");
    }

    private void 깨뜨린다(String dn) throws LDAPException {
        server.modify(dn, new Modification(ModificationType.REPLACE, "userAccountControl", "abc"));
    }

    private LdapProperties DIT_이름기반() {
        var properties = 이름기반();
        properties.setStrategy("dit");
        properties.getDit().setRootDn("ou=company");
        return properties;
    }
}

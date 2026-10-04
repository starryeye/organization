package dev.starryeye.organization.ldap;

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
 * 깨진 값은 뒤에 읽히는 쪽에 둔다(임베디드 서버는 DN 순서로 돌려준다): 앞의 엔트리가 먼저 살아남고 뒤의 것이 건너뛸 엔트리다.
 */
class DitDuplicateIdAccountStatusTest extends EmbeddedLdapSupport {

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
                userAccountControl: abc
                """;
    }

    @Test
    @DisplayName("아이디가 겹쳐 건너뛸 직원도 계정 상태 값이 정수가 아니면 읽기가 실패한다")
    void 건너뛸_직원의_깨진_계정_상태도_실패한다() {
        // given — uid 가 같은 두 직원, 뒤에 읽히는 쪽의 userAccountControl 이 정수가 아니다
        var properties = 이름기반();
        properties.setStrategy("dit");
        properties.getDit().setRootDn("ou=company");
        var strategy = new DitStrategy(properties);

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("userAccountControl")
                .hasMessageContaining("uid=dup,ou=OPS001");
    }
}

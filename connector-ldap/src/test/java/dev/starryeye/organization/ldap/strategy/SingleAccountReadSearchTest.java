package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import dev.starryeye.organization.ldap.EmbeddedLdapSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 사람 읽기가 서버에 보내는 필터가 정말 이스케이프됐는지 — 전략 시험의 판정만으로는 가려지지 않는다. JDK 는 괄호가 이스케이프 안 된 값도 같은 BER 로 보내고,
 * 별표는 존재 필터가 돼 모든 엔트리를 받지만 정확히 같은 id 만 고르는 규칙이 그것을 ABSENT 로 만든다. 그래서 매퍼가 받은 엔트리 수를 센다.
 */
class SingleAccountReadSearchTest extends EmbeddedLdapSupport {

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

                dn: uid=kim,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                cn: kim
                sn: kim

                dn: uid=lee,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: lee
                cn: lee
                sn: lee
                """;
    }

    private int 매퍼가_받은_수(String employeeId) {
        AtomicInteger 수 = new AtomicInteger();
        SingleAccountRead.읽는다(ldapTemplate, "ou=people", "ou=people," + BASE_DN, BASE_DN,
                "(objectClass=inetOrgPerson)", "uid", new String[]{"uid"},
                context -> {
                    수.incrementAndGet();
                    return SingleAccountRead.Candidate.건너뜀();
                }, employeeId, null);
        return 수.get();
    }

    @Test
    @DisplayName("id 에 별표가 있으면 서버가 엔트리를 하나도 돌려주지 않는다 — 별표는 값이지 와일드카드가 아니다")
    // 깨뜨릴 변경: filterEncode 를 빼면 (uid=*) 가 존재 필터가 돼 매퍼가 두 엔트리를 받아 이 시험이 깨진다
    void 별표는_와일드카드가_아니라_값이다() {
        // given, when
        int 받은_수 = 매퍼가_받은_수("*");

        // then
        assertThat(받은_수).isZero();
    }

    @Test
    @DisplayName("대조군 — 정확한 id 는 그 엔트리 하나만 받는다")
    // 깨뜨릴 변경: id 필터를 더하지 않으면 두 엔트리를 받아 이 시험이 깨진다
    void 정확한_id_는_하나만_받는다() {
        // given, when
        int 받은_수 = 매퍼가_받은_수("kim");

        // then
        assertThat(받은_수).isEqualTo(1);
        assertThat(SingleAccountRead.읽는다(ldapTemplate, "ou=people", "ou=people," + BASE_DN, BASE_DN,
                "(objectClass=inetOrgPerson)", "uid", new String[]{"uid"},
                context -> new SingleAccountRead.Candidate("kim", true), "kim", null))
                .isEqualTo(AccountState.ACTIVE);
    }
}

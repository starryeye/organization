package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.NameNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DIT 전략의 한 사람 읽기(설계 2026-11-28 V3·§5.2) — groupOfNames 와 같은 규칙이되 베이스는 {@code root-dn} 이고,
 * 매퍼가 주는 DN 이 베이스 상대라는 점이 다르다.
 */
class DitReadAccountTest extends EmbeddedLdapSupport {

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

                dn: ou=a,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: a

                dn: ou=b,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: b

                dn: ou=outside,dc=example,dc=com
                objectClass: organizationalUnit
                ou: outside

                dn: uid=kim,ou=a,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                cn: kim
                sn: kim

                dn: uid=lee,ou=a,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: lee
                cn: lee
                sn: lee
                pwdAccountLockedTime: 000001010000Z

                dn: uid=dup,ou=a,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: dup
                cn: dup
                sn: dup
                pwdAccountLockedTime: 000001010000Z

                dn: uid=dup,ou=b,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: dup
                cn: dup
                sn: dup

                dn: uid=a b,ou=a,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: a b
                cn: a b
                sn: a b

                dn: uid=p(1),ou=a,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: p(1)
                cn: p1
                sn: p1

                dn: uid=out,ou=outside,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: out
                cn: out
                sn: out
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        var d = properties.getDit();
        d.setRootDn("ou=company");
        d.setOrgUnitFilter("(objectClass=organizationalUnit)");
        d.setGroupIdAttribute("ou");
        d.setUserFilter("(objectClass=inetOrgPerson)");
        d.setUserIdAttribute("uid");
        d.setUserLoginAttribute("uid");
        return properties;
    }

    private static String dn(String uid) {
        return "uid=" + uid + ",ou=a,ou=company," + BASE_DN;
    }

    @Test
    @DisplayName("활성 엔트리가 있으면 ACTIVE 다")
    // 깨뜨릴 변경: 판정에서 active 를 뒤집거나 id 검색을 빼면 이 시험이 깨진다
    void 활성이면_ACTIVE() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "kim", dn("kim"));

        // then
        assertThat(상태).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    @DisplayName("영구 잠금 엔트리뿐이면 BLOCKED 다")
    // 깨뜨릴 변경: 계정 상태(AccountStatus.막혔는가)를 보지 않으면 이 시험이 깨진다
    void 영구_잠금이면_BLOCKED() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "lee", dn("lee"));

        // then
        assertThat(상태).isEqualTo(AccountState.BLOCKED);
    }

    @Test
    @DisplayName("엔트리를 지우면 DN 다시 읽기의 결과 32 를 없음으로 바꿔 ABSENT 다")
    // 깨뜨릴 변경: DN 다시 읽기의 NameNotFoundException 을 그대로 올리면 이 시험이 깨진다
    void 엔트리를_지우면_DN_다시_읽기의_32로_ABSENT() throws Exception {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);
        server.delete(dn("lee"));

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "lee", dn("lee"));

        // then
        assertThat(상태).isEqualTo(AccountState.ABSENT);
    }

    @Test
    @DisplayName("직원 검색 베이스 밖의 엔트리는 고르지도 DN 으로 다시 읽지도 않아 ABSENT 다")
    // 깨뜨릴 변경: 베이스 아래인지 보지 않고 DN 을 읽으면 out 이 ACTIVE 가 된다
    void 검색_베이스_밖은_고르지도_다시_읽지도_않아_ABSENT() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "out", "uid=out,ou=outside," + BASE_DN);

        // then
        assertThat(상태).isEqualTo(AccountState.ABSENT);
    }

    @Test
    @DisplayName("대소문자만 다른 id 는 다른 사람이라 ABSENT 다")
    // 깨뜨릴 변경: id 비교를 equalsIgnoreCase 로 바꾸면 이 시험이 깨진다
    void 대소문자만_다른_id_는_다른_사람이라_ABSENT() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "Kim", null);

        // then
        assertThat(상태).isEqualTo(AccountState.ABSENT);
    }

    @Test
    @DisplayName("id 가 겹치면 하나라도 활성일 때 ACTIVE 다")
    // 깨뜨릴 변경: 첫 엔트리만 보거나 allMatch 로 바꾸면 이 시험이 깨진다
    void id_가_겹치면_하나라도_활성이면_ACTIVE() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "dup", null);

        // then
        assertThat(상태).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    @DisplayName("정규화가 바꾼 id 는 id 검색이 못 찾으니 DN 으로 다시 읽어 ACTIVE 다")
    // 깨뜨릴 변경: id 검색이 비었을 때 DN 다시 읽기를 하지 않으면 활성인 사람이 ABSENT 가 된다
    void 정규화가_바꾼_id_는_DN_으로_다시_읽어_ACTIVE() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "a_b", dn("a b"));

        // then
        assertThat(상태).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    @DisplayName("필터 특수 문자는 이스케이프한다 — 괄호는 그 사람을 고르고, 별표는 모두를 고르지 않는다")
    // 깨뜨릴 변경: 이 시험은 filterEncode 를 빼도 통과한다(JDK 가 같은 BER 로 보내고 정확한 id 선택이 별표를 ABSENT 로 만든다). 이스케이프 자체는 SingleAccountReadSearchTest 가 지킨다. 여기서는 특수 문자 id 의 판정만 본다
    void 필터_특수_문자는_이스케이프한다() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 괄호 = strategy.readAccount(ldapTemplate, "p(1)", null);
        var 별표 = strategy.readAccount(ldapTemplate, "*", null);

        // then
        assertThat(괄호).isEqualTo(AccountState.ACTIVE);
        assertThat(별표).isEqualTo(AccountState.ABSENT);
    }

    @Test
    @DisplayName("저장 한도를 넘는 엔트리는 고르지 않는다")
    // 깨뜨릴 변경: 한도 넘음 엔트리를 건너뛰지 않고 id 로 세면 501바이트 id 가 ACTIVE 가 된다
    void 한도를_넘는_엔트리는_고르지_않는다() throws Exception {
        // given
        var properties = 설정();
        properties.getDit().setUserIdAttribute("employeeNumber");
        var strategy = new DitStrategy(properties, 고정시계);
        server.add("dn: uid=n500,ou=a,ou=company,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: n500",
                "employeeNumber: " + "a".repeat(500), "cn: X", "sn: X");
        server.add("dn: uid=n501,ou=a,ou=company,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: n501",
                "employeeNumber: " + "b".repeat(501), "cn: X", "sn: X");

        // when
        var 한도_안 = strategy.readAccount(ldapTemplate, "a".repeat(500), null);
        var 한도_밖 = strategy.readAccount(ldapTemplate, "b".repeat(501), null);

        // then
        assertThat(한도_안).isEqualTo(AccountState.ACTIVE);
        assertThat(한도_밖).isEqualTo(AccountState.ABSENT);
    }

    @Test
    @DisplayName("직원 검색 베이스가 없으면 id 검색의 NameNotFoundException 이 그대로 오른다")
    // 깨뜨릴 변경: id 검색의 NameNotFoundException 까지 없음으로 바꾸면 설정 오류가 ABSENT 로 묻힌다
    void 검색_베이스가_없으면_NameNotFoundException_이_오른다() {
        // given
        var properties = 설정();
        properties.getDit().setRootDn("ou=nowhere");
        var strategy = new DitStrategy(properties, 고정시계);

        // when, then
        assertThatThrownBy(() -> strategy.readAccount(ldapTemplate, "kim", dn("kim")))
                .isInstanceOf(NameNotFoundException.class);
    }
}

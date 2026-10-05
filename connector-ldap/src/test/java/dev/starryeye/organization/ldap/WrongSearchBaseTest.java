package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.NameNotFoundException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재시도 분류(점검 S23, 설계 2026-10-05 §4.1)가 실제 서버의 결과 코드에서도 맞는다. {@code LdapDirectorySnapshotSourceTest} 는 예외를 손으로 만들어
 * 던지므로 Spring LDAP 이 결과 코드 32(noSuchObject)를 정말 {@code NameNotFoundException} 으로 옮기는지, 페이징 검색 길에서도 그런지는 보지 못한다 —
 * 임베디드 서버에 틀린 검색 베이스로 묻는다.
 */
class WrongSearchBaseTest extends EmbeddedLdapSupport {

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
                cn: Kim
                sn: Kim

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups
                """;
    }

    @Test
    @DisplayName("검색 베이스가 틀리면(결과 코드 32) NameNotFoundException 으로 한 번만 읽고 실패한다 — 다시 읽어도 같으므로 재시도하지 않는다")
    void 틀린_검색_베이스는_한_번만_읽는다() {
        // given — 직원 검색 베이스 오타. 재시도 3번이 설정돼 있고, 전략을 몇 번 불렀는지 센다
        var properties = 이름기반();
        properties.getGroupOfNames().setUserSearchBase("ou=peeple");
        properties.setMaxRetries(3);
        var 전략 = new GroupOfNamesStrategy(properties);
        AtomicInteger 읽은_수 = new AtomicInteger();
        LdapMappingStrategy 세는_전략 = template -> {
            읽은_수.incrementAndGet();
            return 전략.read(template);
        };
        var source = new LdapDirectorySnapshotSource(ldapTemplate, 세는_전략, properties);

        // when, then
        assertThatThrownBy(() -> source.fetchAll().block())
                .isInstanceOf(NameNotFoundException.class)
                .hasMessageContaining("ou=peeple");
        assertThat(읽은_수).hasValue(1);
    }
}

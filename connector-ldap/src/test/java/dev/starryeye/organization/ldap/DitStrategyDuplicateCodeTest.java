package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * design §4.3 / §9: DIT 은 형제 사이에서만 RDN 유일성을 보장하므로, 서로 다른 부모 아래의
 * ou 가 같은 코드로 정규화될 수 있다 — ou=support,ou=DEV001 과 ou=support,ou=OPS001 이
 * 둘 다 코드 "support" 로 뭉개지는 경우가 그렇다. 이때 두 dn 을 하나로 합치지 않고
 * 뒤에 온 엔트리를 스킵 + 경고 로그로 남기는지 검증한다.
 */
class DitStrategyDuplicateCodeTest extends EmbeddedLdapSupport {

    private ListAppender<ILoggingEvent> logAppender;

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
                description: 개발본부

                dn: ou=OPS001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: OPS001
                description: 운영본부

                dn: ou=support,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: support
                description: 개발지원팀

                dn: ou=support,ou=OPS001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: support
                description: 운영지원팀
                """;
    }

    @BeforeEach
    void 로그_수집기를_등록한다() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy"))
                .addAppender(logAppender);
    }

    @AfterEach
    void 로그_수집기를_해제한다() {
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy"))
                .detachAppender(logAppender);
    }

    private LdapProperties 기본설정() {
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

    @Test
    @DisplayName("서로 다른 부모 아래의 ou 가 같은 코드로 충돌하면 하나만 살아남고 경고를 남긴다")
    void 코드가_충돌하면_하나만_살아남고_경고를_남긴다() {
        // given
        var strategy = new DitStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 코드는 하나만 존재하고, DEV001/OPS001 중 정확히 한쪽만 그 코드를 자식으로 갖는다
        assertThat(snapshot.groups()).containsKey("support");
        boolean devHasSupport = snapshot.groups().get("DEV001").members().contains(MemberRef.group("support"));
        boolean opsHasSupport = snapshot.groups().get("OPS001").members().contains(MemberRef.group("support"));
        assertThat(devHasSupport ^ opsHasSupport)
                .as("support 는 DEV001, OPS001 중 정확히 한쪽에만 자식으로 등록돼야 한다")
                .isTrue();

        // and — 경고 로그가 남는다
        List<ILoggingEvent> warnings = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("조직코드"))
                .filter(event -> event.getFormattedMessage().contains("support"))
                .toList();
        assertThat(warnings).isNotEmpty();
    }
}

package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DIT 에서 부모 조직을 찾지 못한 직원(점검 S22). AD 의 {@code CN=Users} 는 OU 가 아니라 컨테이너라 OU 필터에 걸리지 않고, 그 아래 직원은
 * 소속을 찾지 못한다. 이 직원은 직원으로는 적재하되 소속이 없다 — 그러나 소속을 가진 직원이 하나도 없으면 설정이 어긋난 것이다.
 *
 * <p>컨테이너에도 조직 id 속성({@code ou})을 달아 둔다. 그래야 컨테이너가 조직이 되지 못하는 이유가 id 가 없어서가 아니라 <b>OU 필터에 걸리지
 * 않아서</b>라는 것이 테스트에서 분명하다.
 */
class DitMissingParentTest extends EmbeddedLdapSupport {

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

                dn: uid=kim,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                cn: kim
                sn: kim

                dn: cn=Users,dc=example,dc=com
                objectClass: container
                cn: Users
                ou: Users

                dn: uid=lee,cn=Users,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: lee
                cn: lee
                sn: lee
                """;
    }

    @BeforeEach
    void 로그_수집기를_등록한다() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy")).addAppender(logAppender);
    }

    @AfterEach
    void 로그_수집기를_해제한다() {
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy")).detachAppender(logAppender);
    }

    @Test
    @DisplayName("부모 조직을 찾지 못한 직원은 소속 없이 적재되고 회차는 계속된다(점검 S22)")
    void 부모_없는_직원은_소속_없이_적재된다() {
        // given — root-dn 을 베이스 전체로 두어 cn=Users 아래 직원도 읽힌다
        var properties = 이름기반();
        properties.getDit().setRootDn("");

        // when
        var snapshot = new DitStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsKeys("kim", "lee");
        assertThat(snapshot.groups().get("DEV001").members()).containsExactly(MemberRef.user("kim"));
        assertThat(snapshot.groups().values()).noneMatch(group -> group.members().contains(MemberRef.user("lee")));
    }

    @Test
    @DisplayName("부모를 찾지 못한 직원은 경고 한 줄에 담긴다 — 직원마다 한 줄이 아니다")
    void 부모_없는_직원은_경고_한_줄로_남는다() {
        // given
        var properties = 이름기반();
        properties.getDit().setRootDn("");

        // when
        new DitStrategy(properties).read(ldapTemplate);

        // then
        List<String> 경고들 = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(경고들).hasSize(1);
        assertThat(경고들.get(0)).contains("직원 검색").contains("부모 조직").contains("1건").contains("uid=lee,cn=Users");
    }

    @Test
    @DisplayName("소속을 찾은 직원이 하나도 없으면 데이터 오류다 — 아무도 권한 없이 SUCCEEDED 가 되지 않게(점검 S22)")
    void 아무도_소속이_없으면_멈춘다() throws Exception {
        // given — OU 아래 직원 kim 을 지워 직원은 cn=Users 아래 lee 뿐이다
        server.delete("uid=kim,ou=DEV001,ou=company,dc=example,dc=com");
        var properties = 이름기반();
        properties.getDit().setRootDn("");

        // when, then
        assertThatThrownBy(() -> new DitStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("부모 조직").hasMessageContaining("org-unit-filter");
    }

    @Test
    @DisplayName("OU 필터에 컨테이너를 더하면 CN=Users 도 조직이 되어 그 아래 직원이 소속을 찾는다(점검 S22 의 설정 쪽 답)")
    void 필터에_컨테이너를_더하면_소속을_찾는다() {
        // given
        var properties = 이름기반();
        properties.getDit().setRootDn("");
        properties.getDit().setOrgUnitFilter("(|(objectClass=organizationalUnit)(objectClass=container))");

        // when
        var snapshot = new DitStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.groups()).containsKeys("DEV001", "Users");
        assertThat(snapshot.groups().get("Users").members()).containsExactly(MemberRef.user("lee"));
        assertThat(snapshot.groups().get("DEV001").members()).containsExactly(MemberRef.user("kim"));
        assertThat(logAppender.list).as("소속을 모두 찾았으니 건너뛴 것이 없고 경고도 없다")
                .noneMatch(event -> event.getLevel() == Level.WARN);
    }
}

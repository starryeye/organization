package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 식별 속성이 없는 엔트리(점검 M11). 엔트리 하나가 id 를 가질 수 없다고 회차를 멈추지 않는다 — 그 엔트리만 건너뛰고 계속한다.
 * 멈추는 것은 <b>하나도 남지 않을 때</b>뿐이다. 설정한 속성 이름이 틀리면 모든 엔트리에 그 속성이 없으므로 이 경우가 된다.
 *
 * <p>두 갈래 모두 {@link DirectoryDataException} 이라 재시도하지 않는다 — 같은 디렉터리를 몇 번 더 읽어도 그 속성은 생기지 않는다.
 */
class RequiredAttributeMissingTest extends EmbeddedLdapSupport {

    private ListAppender<ILoggingEvent> logAppender;

    /**
     * 직원 {@code kim}·{@code lee} 에게는 {@code employeeNumber} 가 있고 {@code svc} 에게는 없다. OU 는 {@code DEV001} 에만
     * {@code description} 이 있어 DIT 에서 그것을 조직 id 로 읽으면 {@code NOCODE} 가 id 없는 OU 가 된다.
     */
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
                description: ROOT

                dn: ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001
                description: D1

                dn: ou=NOCODE,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: NOCODE

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: uid=kim,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                cn: kim
                sn: kim
                employeeNumber: 1001

                dn: uid=lee,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: lee
                cn: lee
                sn: lee
                employeeNumber: 1002

                dn: uid=svc,ou=NOCODE,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: svc
                cn: svc
                sn: svc

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: uid=kim,ou=DEV001,ou=company,dc=example,dc=com
                member: uid=lee,ou=DEV001,ou=company,dc=example,dc=com
                member: uid=svc,ou=NOCODE,ou=company,dc=example,dc=com
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

    private LdapProperties groupOfNames설정() {
        var properties = 이름기반();
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=company");
        g.setGroupSearchBase("ou=groups");
        return properties;
    }

    private LdapProperties dit설정() {
        var properties = 이름기반();
        properties.setStrategy("dit");
        properties.getDit().setRootDn("ou=company");
        return properties;
    }

    @Test
    @DisplayName("식별 속성이 없는 직원 하나는 건너뛰고 나머지로 계속한다(점검 M11) — 그 직원을 가리키는 member 만 빠진다")
    void id_없는_직원은_건너뛴다() {
        // given
        var properties = groupOfNames설정();
        properties.getGroupOfNames().setUserIdAttribute("employeeNumber");

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("1001", "1002");
        assertThat(snapshot.groups().get("DEV").members())
                .containsExactlyInAnyOrder(MemberRef.user("1001"), MemberRef.user("1002"));
    }

    @Test
    @DisplayName("건너뛴 직원이 여럿이어도 경고는 검색당 한 줄이다 — 건수와 예시 DN 을 담는다")
    void 건너뛴_직원은_경고_한_줄로_남는다() throws Exception {
        // given — id 없는 직원을 셋 더 둔다(모두 넷)
        for (String uid : List.of("svc2", "svc3", "svc4")) {
            server.add("dn: uid=" + uid + ",ou=NOCODE,ou=company," + BASE_DN,
                    "objectClass: inetOrgPerson", "uid: " + uid, "cn: " + uid, "sn: " + uid);
        }
        var properties = groupOfNames설정();
        properties.getGroupOfNames().setUserIdAttribute("employeeNumber");

        // when
        new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        List<String> 경고들 = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("직원 검색"))
                .toList();
        assertThat(경고들).hasSize(1);
        assertThat(경고들.get(0)).contains("4건").contains("employeeNumber")
                .contains("uid=svc,").contains("uid=svc4,");
    }

    @Test
    @DisplayName("직원 모두에게 식별 속성이 없으면 데이터 오류다 — 설정한 속성 이름이 틀린 경우")
    void 직원_모두_id_가_없으면_멈춘다() {
        // given
        var properties = groupOfNames설정();
        properties.getGroupOfNames().setUserIdAttribute("employeeNumbr");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("직원 검색").hasMessageContaining("employeeNumbr");
    }

    @Test
    @DisplayName("그룹 모두에게 식별 속성이 없으면 데이터 오류다")
    void 그룹_모두_id_가_없으면_멈춘다() {
        // given
        var properties = groupOfNames설정();
        properties.getGroupOfNames().setGroupIdAttribute("gidNumber");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("조직 검색").hasMessageContaining("gidNumber");
    }

    @Test
    @DisplayName("DIT — 식별 속성이 없는 OU 하나는 건너뛴다 — 그 OU 는 조직이 되지 않고, 산하 직원은 소속 없이 적재된다")
    void id_없는_OU_는_건너뛰고_산하_직원은_소속_없이_남는다() {
        // given — description 을 조직 id 로 읽는다. NOCODE 에는 description 이 없다
        var properties = dit설정();
        properties.getDit().setGroupIdAttribute("description");
        properties.getDit().setGroupNameAttribute("ou");

        // when
        var snapshot = new DitStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.groups()).containsOnlyKeys("ROOT", "D1");
        assertThat(snapshot.groups().get("D1").members())
                .containsExactlyInAnyOrder(MemberRef.user("kim"), MemberRef.user("lee"));
        assertThat(snapshot.groups().get("ROOT").members()).containsExactly(MemberRef.group("D1"));
        assertThat(snapshot.users()).containsOnlyKeys("kim", "lee", "svc");
        assertThat(snapshot.groups().values()).noneMatch(group -> group.members().contains(MemberRef.user("svc")));
    }

    @Test
    @DisplayName("DIT — 식별 속성이 없는 직원 하나는 건너뛰고 나머지로 계속한다")
    void dit_id_없는_직원은_건너뛴다() {
        // given
        var properties = dit설정();
        properties.getDit().setUserIdAttribute("employeeNumber");

        // when
        var snapshot = new DitStrategy(properties).read(ldapTemplate);

        // then — 조직 id 는 ou 라 모든 OU 가 남는다
        assertThat(snapshot.users()).containsOnlyKeys("1001", "1002");
        assertThat(snapshot.groups().get("DEV001").members())
                .containsExactlyInAnyOrder(MemberRef.user("1001"), MemberRef.user("1002"));
    }

    @Test
    @DisplayName("DIT — 모든 OU 에 식별 속성이 없으면 데이터 오류다")
    void 모든_OU_에_id_가_없으면_멈춘다() {
        // given
        var properties = dit설정();
        properties.getDit().setGroupIdAttribute("gidNumber");

        // when, then
        assertThatThrownBy(() -> new DitStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("조직 검색").hasMessageContaining("gidNumber");
    }

    @Test
    @DisplayName("DIT — 모든 직원에게 식별 속성이 없으면 데이터 오류다")
    void dit_직원_모두_id_가_없으면_멈춘다() {
        // given
        var properties = dit설정();
        properties.getDit().setUserIdAttribute("employeeNumbr");

        // when, then
        assertThatThrownBy(() -> new DitStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("직원 검색").hasMessageContaining("employeeNumbr");
    }
}

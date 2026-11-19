package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.SkippedEntryObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * groupOfNames 전략의 길이 검사(설계 2026-11-19 S2·S3) — 정규화한 id(직원 500·조직 250바이트)나 절대 DN(1024바이트)이 저장 한도를 넘는
 * 엔트리는 나머지 속성을 읽지 않고 건너뛴다. 식별 속성은 RDN 이 아닌 문자열 속성({@code employeeNumber}·{@code businessCategory})이다.
 */
class GroupOfNamesLengthLimitTest extends EmbeddedLdapSupport {

    private ListAppender<ILoggingEvent> logAppender;
    private final List<String> 관찰 = new ArrayList<>();

    /** 한도 안의 직원 하나와 한도 안의 조직 하나 — 사람 소속이 하나도 없는 그림은 데이터 오류라서 둔다 */
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

                dn: uid=ok,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: ok
                employeeNumber: ok
                cn: Ok
                sn: Ok

                dn: cn=gok,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: gok
                businessCategory: gok
                member: uid=ok,ou=people,dc=example,dc=com
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

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=people");
        g.setUserFilter("(objectClass=inetOrgPerson)");
        g.setUserIdAttribute("employeeNumber");
        g.setUserLoginAttribute("uid");
        g.setGroupSearchBase("ou=groups");
        g.setGroupFilter("(objectClass=groupOfNames)");
        g.setGroupIdAttribute("businessCategory");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }

    private GroupOfNamesStrategy 전략() {
        return new GroupOfNamesStrategy(설정(), Clock.systemUTC(), (검색, 사유, 건수) -> 관찰.add(검색 + ":" + 사유 + ":" + 건수));
    }

    private void 직원을_더한다(String rdn값, String 식별값, String... 더한줄) throws Exception {
        List<String> 줄 = new ArrayList<>(List.of(
                "dn: uid=" + rdn값 + ",ou=people,dc=example,dc=com",
                "objectClass: inetOrgPerson", "uid: " + rdn값, "employeeNumber: " + 식별값, "cn: X", "sn: X"));
        줄.addAll(List.of(더한줄));
        server.add(줄.toArray(String[]::new));
    }

    private void 조직을_더한다(String rdn값, String 식별값, String... 더한줄) throws Exception {
        List<String> 줄 = new ArrayList<>(List.of(
                "dn: cn=" + rdn값 + ",ou=groups,dc=example,dc=com",
                "objectClass: groupOfNames", "cn: " + rdn값, "businessCategory: " + 식별값));
        줄.addAll(List.of(더한줄));
        server.add(줄.toArray(String[]::new));
    }

    private String 경고() {
        return String.join("\n", logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    @DisplayName("직원 id 가 500바이트면 남고 501바이트면 빠진다")
    void 직원_id_가_500바이트면_남고_501바이트면_빠진다() throws Exception {
        직원을_더한다("u500", "a".repeat(500));
        직원을_더한다("u501", "b".repeat(501));

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.users()).containsKeys("a".repeat(500)).doesNotContainKey("b".repeat(501));
        assertThat(경고()).contains("값이 저장 한도를 넘음 1건").contains("(id 501바이트, 한도 500)");
    }

    @Test
    @DisplayName("조직 id 가 250바이트면 남고 251바이트면 빠진다")
    void 조직_id_가_250바이트면_남고_251바이트면_빠진다() throws Exception {
        조직을_더한다("g250", "a".repeat(250));
        조직을_더한다("g251", "b".repeat(251));

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.groups()).containsKeys("a".repeat(250)).doesNotContainKey("b".repeat(251));
        assertThat(경고()).contains("조직 검색").contains("값이 저장 한도를 넘음 1건").contains("(id 251바이트, 한도 250)");
    }

    @Test
    @DisplayName("한글 조직 id 는 바이트로 재어 빠진다")
    void 한글_조직_id_는_바이트로_재어_빠진다() throws Exception {
        조직을_더한다("gko", "가".repeat(84));

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.groups()).containsOnlyKeys("gok");
        assertThat(경고()).contains("(id 252바이트, 한도 250)");
    }

    @Test
    @DisplayName("절대 DN 이 1024바이트를 넘으면 빠진다")
    void 절대_DN_이_1024바이트를_넘으면_빠진다() throws Exception {
        직원을_더한다("b".repeat(1100), "shortid");

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.users()).containsOnlyKeys("ok");
        assertThat(경고()).contains("값이 저장 한도를 넘음 1건").contains("(DN ").contains("…'");
    }

    @Test
    @DisplayName("한도를 넘은 직원은 다른 속성을 읽지 않는다")
    void 한도를_넘은_직원은_다른_속성을_읽지_않는다() throws Exception {
        직원을_더한다("bad", "c".repeat(501), "userAccountControl: abc", "primaryGroupID: abc");

        assertThatCode(() -> 전략().read(ldapTemplate)).doesNotThrowAnyException();
        assertThat(전략().read(ldapTemplate).users()).containsOnlyKeys("ok");
    }

    @Test
    @DisplayName("직원이 모두 한도를 넘으면 멈춘다")
    void 모두_넘으면_멈춘다() throws Exception {
        server.delete("uid=ok,ou=people,dc=example,dc=com");
        직원을_더한다("u1", "a".repeat(501));
        직원을_더한다("u2", "b".repeat(501));

        assertThatThrownBy(() -> 전략().read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("하나도 남지 않았다")
                .hasMessageContaining("값이 저장 한도를 넘음");
    }

    @Test
    @DisplayName("전략이 관찰자에 건너뛴 수를 알린다")
    void 전략이_관찰자에_건너뛴_수를_알린다() throws Exception {
        직원을_더한다("u501", "b".repeat(501));

        전략().read(ldapTemplate);

        assertThat(관찰).contains("직원:한도_넘음:1");
    }

    @Test
    @DisplayName("빠진 직원을 가리키는 member 는 대조되지 않은 값으로 센다")
    void 빠진_직원을_가리키는_member_는_대조되지_않은_값으로_센다() throws Exception {
        직원을_더한다("u501", "b".repeat(501));
        조직을_더한다("gm", "gm",
                "member: uid=ok,ou=people,dc=example,dc=com", "member: uid=u501,ou=people,dc=example,dc=com");

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.groups().get("gm").members()).containsExactly(MemberRef.user("ok"));
        assertThat(경고()).contains("member 값 1건을 건너뛰었다");
    }

    @Test
    @DisplayName("한도를 넘은 그룹은 빠지고 그 member 는 해석하지 않는다")
    void 한도를_넘은_그룹은_빠지고_그_member_는_해석하지_않는다() throws Exception {
        // member 범위 이어 읽기를 하지 않는 것은 RawEntry.한도_넘음 이 membersComplete=true 로 두는 코드가 보장한다 — 이 시험은 결과만 본다
        조직을_더한다("gbad", "d".repeat(251), "member: 이건 DN 이 아니다");

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.groups()).doesNotContainKey("d".repeat(251));
        assertThat(snapshot.groups()).containsKey("gok");
        assertThat(snapshot.users()).containsOnlyKeys("ok");
    }
}

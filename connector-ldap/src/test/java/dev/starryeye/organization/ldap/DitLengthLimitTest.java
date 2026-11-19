package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
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
 * DIT 전략의 길이 검사(설계 2026-11-19 S2·S3) — 잰 값은 베이스를 붙인 절대 DN 이고 예시에도 그것을 싣는다.
 * 식별 속성은 RDN 이 아닌 문자열 속성({@code employeeNumber}·{@code businessCategory})이다.
 */
class DitLengthLimitTest extends EmbeddedLdapSupport {

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

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company
                businessCategory: company

                dn: uid=ok,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: ok
                employeeNumber: ok
                cn: Ok
                sn: Ok
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
        properties.setStrategy("dit");
        var d = properties.getDit();
        d.setRootDn("ou=company");
        d.setOrgUnitFilter("(objectClass=organizationalUnit)");
        d.setGroupIdAttribute("businessCategory");
        d.setGroupNameAttribute("description");
        d.setUserFilter("(objectClass=inetOrgPerson)");
        d.setUserIdAttribute("employeeNumber");
        d.setUserLoginAttribute("uid");
        return properties;
    }

    private DitStrategy 전략() {
        return new DitStrategy(설정(), Clock.systemUTC(), (검색, 사유, 건수) -> 관찰.add(검색 + ":" + 사유 + ":" + 건수));
    }

    private void 직원을_더한다(String 부모, String rdn값, String 식별값, String... 더한줄) throws Exception {
        List<String> 줄 = new ArrayList<>(List.of(
                "dn: uid=" + rdn값 + "," + 부모 + "ou=company,dc=example,dc=com",
                "objectClass: inetOrgPerson", "uid: " + rdn값, "employeeNumber: " + 식별값, "cn: X", "sn: X"));
        줄.addAll(List.of(더한줄));
        server.add(줄.toArray(String[]::new));
    }

    private void 조직을_더한다(String rdn값, String 식별값) throws Exception {
        server.add("dn: ou=" + rdn값 + ",ou=company,dc=example,dc=com",
                "objectClass: organizationalUnit", "ou: " + rdn값, "businessCategory: " + 식별값);
    }

    private String 경고() {
        return String.join("\n", logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    @DisplayName("직원 id 가 500바이트면 남고 501바이트면 빠진다")
    void 직원_id_가_500바이트면_남고_501바이트면_빠진다() throws Exception {
        직원을_더한다("", "u500", "a".repeat(500));
        직원을_더한다("", "u501", "b".repeat(501));

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.users()).containsKeys("a".repeat(500)).doesNotContainKey("b".repeat(501));
        assertThat(경고()).contains("값이 저장 한도를 넘음 1건")
                .contains("dn='uid=u501,ou=company,dc=example,dc=com'(id 501바이트, 한도 500)");
    }

    @Test
    @DisplayName("조직 id 가 250바이트면 남고 251바이트면 빠진다")
    void 조직_id_가_250바이트면_남고_251바이트면_빠진다() throws Exception {
        조직을_더한다("o250", "a".repeat(250));
        조직을_더한다("o251", "b".repeat(251));

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.groups()).containsKeys("a".repeat(250)).doesNotContainKey("b".repeat(251));
        assertThat(경고()).contains("조직 검색").contains("(id 251바이트, 한도 250)");
    }

    @Test
    @DisplayName("한글 조직 id 는 바이트로 재어 빠진다")
    void 한글_조직_id_는_바이트로_재어_빠진다() throws Exception {
        조직을_더한다("oko", "가".repeat(84));

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.groups()).containsOnlyKeys("company");
        assertThat(경고()).contains("(id 252바이트, 한도 250)");
    }

    @Test
    @DisplayName("절대 DN 이 1024바이트를 넘으면 빠진다")
    void 절대_DN_이_1024바이트를_넘으면_빠진다() throws Exception {
        직원을_더한다("", "b".repeat(1100), "shortid");

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.users()).containsOnlyKeys("ok");
        assertThat(경고()).contains("값이 저장 한도를 넘음 1건").contains("(DN ").contains("…'");
    }

    @Test
    @DisplayName("상대 DN 은 1024바이트 이하여도 베이스를 붙인 절대 DN 이 넘으면 빠지고, 정확히 1024바이트면 남는다")
    void 절대_DN_기준으로_잰다() throws Exception {
        // 레코드의 DN 은 베이스 상대다: "uid=<값>,ou=company"(15바이트 + 값). 절대 DN 은 ",dc=example,dc=com"(18바이트)이 더 붙어 33바이트 + 값이다.
        // 값 991자 → 절대 1024(남음), 값 992자 → 상대 1007·절대 1025(빠짐)
        직원을_더한다("", "a".repeat(991), "exact1024");
        직원을_더한다("", "b".repeat(992), "over1025");

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.users()).containsKeys("exact1024").doesNotContainKey("over1025");
        assertThat(경고()).contains("(DN 1025바이트, 한도 1024)");
    }

    @Test
    @DisplayName("한도를 넘은 직원은 다른 속성을 읽지 않는다")
    void 한도를_넘은_직원은_다른_속성을_읽지_않는다() throws Exception {
        직원을_더한다("", "bad", "c".repeat(501), "userAccountControl: abc");

        assertThatCode(() -> 전략().read(ldapTemplate)).doesNotThrowAnyException();
        assertThat(전략().read(ldapTemplate).users()).containsOnlyKeys("ok");
    }

    @Test
    @DisplayName("직원이 모두 한도를 넘으면 멈춘다")
    void 모두_넘으면_멈춘다() throws Exception {
        server.delete("uid=ok,ou=company,dc=example,dc=com");
        직원을_더한다("", "u1", "a".repeat(501));
        직원을_더한다("", "u2", "b".repeat(501));

        assertThatThrownBy(() -> 전략().read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("하나도 남지 않았다")
                .hasMessageContaining("값이 저장 한도를 넘음");
    }

    @Test
    @DisplayName("전략이 관찰자에 건너뛴 수를 알린다")
    void 전략이_관찰자에_건너뛴_수를_알린다() throws Exception {
        직원을_더한다("", "u501", "b".repeat(501));

        전략().read(ldapTemplate);

        assertThat(관찰).contains("직원:한도_넘음:1");
    }

    @Test
    @DisplayName("한도를 넘어 빠진 OU 아래 직원은 소속 없이 남는다")
    void 한도를_넘어_빠진_OU_아래_직원은_소속_없이_남는다() throws Exception {
        조직을_더한다("big", "d".repeat(251));
        직원을_더한다("ou=big,", "under", "under");

        var snapshot = 전략().read(ldapTemplate);

        assertThat(snapshot.users()).containsKey("under");
        assertThat(snapshot.groups().values()).noneMatch(g -> g.members().stream().anyMatch(m -> m.id().equals("under")));
        assertThat(경고()).contains("부모 조직을 찾지 못해 소속 없이 적재 1건");
    }
}

package dev.starryeye.organization.ldap.fixture;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.ldap.LdapProperties;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더러가 심은 것을 커넥터가 그대로 읽어내는지 — <b>왕복</b>으로 확인한다.
 *
 * <p>LDIF 문자열을 눈으로 대조하는 테스트는 형식만 보고 의미를 못 본다. 실제 서버에
 * 임포트하고 실제 전략으로 읽어 조직도와 맞춰보면, DN 규칙·member 판별·페이징이 한꺼번에
 * 검증된다. E2E 가 이 왕복 위에 서므로 여기가 어긋나면 그 위는 전부 헛것이다.
 */
class LdifRendererTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final String BIND_DN = "cn=admin," + BASE_DN;
    private static final String BIND_PASSWORD = "adminpassword";

    private static final OrgChart CHART = OrgChartFixture.오천명();
    private static final LdifRenderer RENDERER = new LdifRenderer(BASE_DN);

    private static InMemoryDirectoryServer server;
    private static DirectorySnapshot 읽은것;

    @BeforeAll
    static void 서버를_띄우고_읽는다() throws Exception {
        String ldif = RENDERER.render(CHART);

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("fixture", 0));
        // 빈 조직은 groupOfNames 의 member 필수 제약에 걸린다. 스키마 검사를 끄는 것은
        // 그 형태를 일부러 살려 빈 델타 경로를 태우기 위해서다.
        config.setSchema(null);

        server = new InMemoryDirectoryServer(config);
        server.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8))));
        server.startListening();

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl("ldap://localhost:" + server.getListenPort());
        contextSource.setBase(BASE_DN);
        contextSource.setUserDn(BIND_DN);
        contextSource.setPassword(BIND_PASSWORD);
        contextSource.afterPropertiesSet();

        LdapTemplate template = new LdapTemplate(contextSource);
        template.setIgnorePartialResultException(true);
        template.setIgnoreSizeLimitExceededException(false);

        읽은것 = new GroupOfNamesStrategy(설정()).read(template);
    }

    @AfterAll
    static void 서버를_내린다() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    @Test
    @DisplayName("심은 직원이 그대로 다시 읽힌다 — 한 명도 빠지지 않는다")
    void 직원이_왕복한다() {
        // then
        assertThat(읽은것.users()).hasSameSizeAs(CHART.snapshot().users());
        assertThat(읽은것.users().keySet()).isEqualTo(CHART.snapshot().users().keySet());

        // externalId 는 조직도가 모르는 값(LDAP 이 DN 을 채운다)이라 나머지만 맞춘다
        CHART.snapshot().users().forEach((id, 심은것) -> {
            DirectoryUser 읽힌것 = 읽은것.users().get(id);
            assertThat(읽힌것.displayName()).isEqualTo(심은것.displayName());
            assertThat(읽힌것.email()).isEqualTo(심은것.email());
        });
    }

    @Test
    @DisplayName("조직과 멤버십이 그대로 다시 읽힌다 — 사람/조직 판별까지 포함")
    void 조직이_왕복한다() {
        // then
        assertThat(읽은것.groups().keySet()).isEqualTo(CHART.snapshot().groups().keySet());

        CHART.snapshot().groups().forEach((code, 심은것) -> {
            DirectoryGroup 읽힌것 = 읽은것.groups().get(code);
            assertThat(읽힌것.displayName()).isEqualTo(심은것.displayName());
            // member DN 이 사람인지 조직인지 전략이 스스로 갈라야 한다.
            // 여기서 어긋나면 롤업 간선이 통째로 사라지거나 사람이 조직으로 뒤바뀐다.
            assertThat(읽힌것.members())
                    .as("조직 %s 의 멤버", code)
                    .isEqualTo(심은것.members());
        });
    }

    @Test
    @DisplayName("DN 은 전략이 재구성하는 형태와 같아야 한다 — externalId 가 어긋나면 안 된다")
    void DN_규칙이_전략과_일치한다() {
        // given
        String 직원 = CHART.landmarks().L6직속직원();
        String 조직 = CHART.landmarks().대상팀();

        // then
        assertThat(읽은것.users().get(직원).externalId()).isEqualTo(RENDERER.userDn(직원));
        assertThat(읽은것.groups().get(조직).externalId()).isEqualTo(RENDERER.groupDn(조직));
    }

    @Test
    @DisplayName("빈 조직도 살아서 읽힌다 — 빈 델타 경로가 실제로 존재한다")
    void 빈_조직이_읽힌다() {
        // given, when, then
        CHART.landmarks().빈조직들().forEach(code ->
                assertThat(읽은것.groups().get(code).members()).isEmpty());
    }

    @Test
    @DisplayName("페이지 크기(500)를 넘는 규모를 페이징으로 전부 읽는다")
    void 페이징으로_전부_읽는다() {
        // then — 한 페이지에 담기지 않는 규모여야 페이징이 검증된다
        assertThat(읽은것.users().size()).isGreaterThan(설정().getPageSize());
    }

    @Test
    @DisplayName("LDIF 로 그대로 쓸 수 없는 값은 조용히 심지 않고 즉시 깨뜨린다")
    void 위험한_값은_거부한다() {
        // given — 줄바꿈이 든 표시명. 그대로 쓰면 다음 줄이 속성으로 오해된다
        var 위험한조직도 = new OrgChart(
                new DirectorySnapshot(
                        java.util.Map.of(),
                        java.util.Map.of("X", new DirectoryGroup("X", null, "줄\n바꿈", java.util.Set.of()))),
                CHART.landmarks());

        // when, then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RENDERER.render(위험한조직도))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    private static LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setPageSize(500);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase(LdifRenderer.USER_OU);
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setUserNameAttribute("displayName");
        g.setUserMailAttribute("mail");
        g.setGroupSearchBase(LdifRenderer.GROUP_OU);
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }
}

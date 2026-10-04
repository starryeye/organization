package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.authz.fixture.ScaleVerification;
import dev.starryeye.organization.core.fixture.ChartExpectation;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.ldap.fixture.LdifRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 기본값({@code entryUUID})으로 오천명 조직도를 LDAP 전체 동기화한다 — 설계 2026-10-04 §8 "규모".
 *
 * <p>{@link LdapScaleSyncCostTest} 와 <b>같은 조직도·같은 시드</b>다. 그쪽은 테스트 프로필이 못박은 이름 기반 id 로 도는 그대로 두고, 이쪽만
 * 식별 속성을 {@code entryUUID} 로 되돌린다. id 가 서버가 만든 UUID 라서 조직도의 아이디(uid/cn)와 달라진다 — SCIM 규모 테스트가 서버 발급 id 로
 * 기대값을 번역하듯, 여기서는 시드한 디렉터리에서 <b>이름 → entryUUID 표</b>를 한 번 읽어 기대 조직도의 아이디를 번역한다. 번역한 기대값을 같은
 * 하네스 검증(DynamoDB 대조·OpenFGA 양성·음성·롤업 + OpenFGA 직접 질의)에 건네므로, 개수만이 아니라 <b>누가 어디 속하는지</b>까지 맞아야 통과한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ScaleTest
class LdapEntryUuidScaleTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final OrgChart CHART = OrgChartFixture.오천명();

    /** UnboundID 의 entryUUID 모양 — 소문자 16진수 8-4-4-4-12. */
    private static final Pattern UUID_모양 = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    static InMemoryDirectoryServer LDAP;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("entryuuid", 0));
        // 스키마 검사를 켜 둔다 — LdapScaleSyncCostTest 와 같은 이유다(임베디드 서버가 실제 서버보다 관대하면 존재할 수 없는 형태를 검증하게 된다)

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                new LdifRenderer(BASE_DN).render(CHART).getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        // 프로필이 못박은 이름 기반 id 를 운영 기본값으로 되돌린다
        registry.add("ldap.group-of-names.user-id-attribute", () -> "entryUUID");
        registry.add("ldap.group-of-names.group-id-attribute", () -> "entryUUID");
        registry.add("ldap.group-of-names.user-login-attribute", () -> "uid");
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;
    @Autowired StoreBootstrapper bootstrapper;

    /**
     * 시드한 디렉터리를 한 번 읽어 만든 표 — 조직도의 아이디(직원 {@code uid}, 조직 {@code cn})에서 서버가 만든 {@code entryUUID} 로.
     * 직원과 조직의 이름이 겹치거나 한 이름이 두 엔트리에 있으면 표가 모호해지므로 멈춘다.
     */
    private static Map<String, String> 이름에서_entryUUID_로() throws LDAPException {
        Map<String, String> 표 = new LinkedHashMap<>();
        var 결과 = LDAP.search(BASE_DN, SearchScope.SUB, "(|(objectClass=inetOrgPerson)(objectClass=groupOfNames))",
                "entryUUID", "uid", "cn", "objectClass");
        for (SearchResultEntry entry : 결과.getSearchEntries()) {
            String 이름 = entry.hasObjectClass("inetOrgPerson") ? entry.getAttributeValue("uid") : entry.getAttributeValue("cn");
            String entryUUID = entry.getAttributeValue("entryUUID");
            if (이름 == null || entryUUID == null) {
                throw new IllegalStateException("이름이나 entryUUID 가 없는 엔트리: " + entry.getDN());
            }
            String 전 = 표.put(이름, entryUUID);
            if (전 != null) {
                throw new IllegalStateException("같은 이름이 두 엔트리에 있어 표가 모호하다: " + 이름 + " → " + 전 + ", " + entryUUID);
            }
        }
        return 표;
    }

    @Test
    @DisplayName("entryUUID 로 조직도 전체 동기화가 한 회차로 끝나고, 번역한 기대값으로 하네스 검증을 통과하며, 직원·조직 id 는 모두 UUID 다")
    void entryUUID_로_전체동기화_실비를_잰다() throws Exception {
        // given — 이름 → entryUUID 표로 기대 조직도의 아이디를 번역한다. 표에 없는 아이디는 조용히 두지 않고 멈춘다
        Map<String, String> 표 = 이름에서_entryUUID_로();
        OrgChart 기대 = CHART.아이디를_바꾼다(id -> {
            String entryUUID = 표.get(id);
            if (entryUUID == null) {
                throw new IllegalStateException("디렉터리에 없는 아이디라 entryUUID 로 번역할 수 없다: " + id);
            }
            return entryUUID;
        });
        int 직원수 = CHART.snapshot().users().size();
        int 조직수 = CHART.snapshot().groups().size();
        assertThat(표).as("시드한 엔트리(직원 + 조직)마다 표가 하나씩").hasSize(직원수 + 조직수);
        int 기대튜플 = ChartExpectation.of(기대).있어야할튜플().size();

        // when
        long t0 = System.currentTimeMillis();
        SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(기대튜플);
        long 싱크 = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 기대);
        long 검증시간 = System.currentTimeMillis() - t1;

        var 상태 = state.loadAll().block(Duration.ofMinutes(5));
        System.out.printf("%n=== LDAP 전체 동기화 실비 (entryUUID) ===%n직원 %d명 / 조직 %d개 / 튜플 %d건%n동기화 %.1f초 / 검증 %.1f초%n",
                상태 == null ? -1 : 상태.users().size(), 상태 == null ? -1 : 상태.groups().size(), 기대튜플,
                싱크 / 1000.0, 검증시간 / 1000.0);

        // then — 직원 수와 조직 수가 시드와 같고, 모든 id 가 UUID 모양이다(이름 id 가 하나라도 남으면 잡는다)
        assertThat(상태).isNotNull();
        assertThat(상태.users()).hasSize(직원수);
        assertThat(상태.groups()).hasSize(조직수);
        assertThat(UUID가_아닌_것(상태.users().keySet())).as("UUID 모양이 아닌 직원 id").isEmpty();
        assertThat(UUID가_아닌_것(상태.groups().keySet())).as("UUID 모양이 아닌 조직 id").isEmpty();
    }

    /** 실패 메시지가 5천 개를 다 찍지 않도록 앞의 몇 개만 돌려준다. */
    private static List<String> UUID가_아닌_것(Iterable<String> ids) {
        List<String> 어긋남 = new ArrayList<>();
        for (String id : ids) {
            if (!UUID_모양.matcher(id).matches() && 어긋남.size() < 5) {
                어긋남.add(id);
            }
        }
        return 어긋남;
    }
}

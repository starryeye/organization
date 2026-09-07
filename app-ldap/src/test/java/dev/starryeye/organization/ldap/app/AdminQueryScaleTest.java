package dev.starryeye.organization.ldap.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.ldap.fixture.LdifRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동기화된 5,000명을 <b>운영자가 실제로 보는 화면</b>으로 조회한다.
 *
 * <p>하네스 ①은 {@code state.loadAll()} 로 DynamoDB 를 직접 읽는다. 그것과 <b>admin 조회가
 * 같은 답을 주는가</b>는 다른 질문이다 — 사이에 커서 페이징, 상위 계층 조립, 디버깅용
 * Check 컬럼이 끼어 있고 <b>전부 규모에 민감한 것들</b>이다.
 *
 * <p>특히 커서 페이징은 규모가 커야 실패가 드러난다. 500명짜리 조직을 100개씩 끊어 읽을 때
 * 한 명이 두 페이지에 걸치거나 경계에서 빠지면, 20명짜리 테스트로는 절대 안 보인다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminQueryScaleTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final OrgChart 기대 = OrgChartFixture.오천명();
    /** 컨트롤러의 MAX_LIMIT. 이 값으로 끊어 읽어야 페이지가 여러 장 나온다. */
    private static final int 최대limit = 100;

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    static InMemoryDirectoryServer LDAP;
    private static boolean 동기화됨;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("adminquery", 0));
        config.setSchema(null);

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                new LdifRenderer(BASE_DN).render(기대).getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;

    @BeforeAll
    static void 표시() {
        동기화됨 = false;
    }

    /** 테스트마다 다시 채우면 회차마다 40초가 든다. 한 번만 채우고 전부 그 위에서 조회한다. */
    private void 한번만_동기화한다() {
        if (동기화됨) {
            return;
        }
        client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                .post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("SUCCEEDED");

        var 결과 = new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10));
        assertThat(결과).isNotNull();
        assertThat(결과.어긋났는가())
                .as("조회를 보기 전에 적재부터 맞아야 한다: " + (결과 == null ? "" : 결과.요약()))
                .isFalse();
        동기화됨 = true;
    }

    @Test
    @DisplayName("500명 조직의 멤버를 커서로 끝까지 읽으면 한 명도 빠지거나 겹치지 않는다")
    void 대형조직_멤버를_커서로_전부_읽는다() {
        // given
        한번만_동기화한다();
        String 대형조직 = 기대.landmarks().대형조직();
        Set<String> 기대멤버 = 기대.snapshot().groups().get(대형조직).members().stream()
                .filter(member -> member.type() == MemberType.USER)
                .map(MemberRef::id)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        // when — 100개씩 끊어 커서를 따라간다. 최소 다섯 장이 나온다
        List<String> 읽은것 = new ArrayList<>();
        String cursor = null;
        int 페이지수 = 0;
        do {
            JsonNode page = 조회한다("/admin/organizations/" + 대형조직 + "/members"
                    + "?limit=" + 최대limit + (cursor == null ? "" : "&cursor={c}"),
                    cursor == null ? new Object[0] : new Object[]{cursor});
            page.get("items").forEach(item -> 읽은것.add(item.get("employeeId").asText()));
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
            페이지수++;
        } while (cursor != null && 페이지수 < 50);

        // then
        assertThat(페이지수).as("한 장에 다 담기면 커서 페이징이 검증되지 않는다").isGreaterThan(1);
        assertThat(읽은것).as("한 명이 두 페이지에 걸치면 여기서 잡힌다").doesNotHaveDuplicates();
        assertThat(Set.copyOf(읽은것))
                .as("경계에서 빠진 사람이 있으면 여기서 잡힌다")
                .isEqualTo(기대멤버);
    }

    @Test
    @DisplayName("멤버 조회의 Check 컬럼이 전부 true — null 이면 Check 자체가 실패한 것이다")
    void 멤버조회의_Check컬럼이_일치한다() {
        // given
        한번만_동기화한다();

        // when
        JsonNode page = 조회한다("/admin/organizations/" + 기대.landmarks().대형조직()
                + "/members?limit=" + 최대limit);

        // then — 이 컬럼은 DynamoDB 가 요구하는 값과 OpenFGA 의 실제 판정을 나란히 보여주는
        // 디버깅용이다. 전부 맞는 상태에서 true 가 아니면 컬럼 자체를 믿을 수 없다
        assertThat(page.get("items")).isNotEmpty();
        page.get("items").forEach(item -> {
            assertThat(item.hasNonNull("openFgaCheck"))
                    .as("Check 호출이 실패하면 null 이 된다: %s", item.get("employeeId").asText())
                    .isTrue();
            assertThat(item.get("openFgaCheck").asBoolean())
                    .as("멤버인데 Check 가 false 다: %s", item.get("employeeId").asText())
                    .isTrue();
        });
    }

    @Test
    @DisplayName("가장 깊은 직원의 상세 조회가 조상 체인 전부를 경로로 보여준다")
    void 깊은직원의_경로가_조상체인과_같다() {
        // given
        한번만_동기화한다();
        String 직원 = 기대.landmarks().L6직속직원();

        // when
        JsonNode detail = 조회한다("/admin/employees/" + 직원);

        // then
        assertThat(detail.get("truncated").asBoolean()).as("상한에 걸릴 규모가 아니다").isFalse();

        Set<String> 경로조직 = new LinkedHashSet<>();
        detail.get("paths").forEach(path -> {
            경로조직.add(path.get("orgCode").asText());
            assertThat(path.get("shouldHaveAccess").asBoolean()).isTrue();
            assertThat(path.hasNonNull("openFgaCheck")).isTrue();
            assertThat(path.get("openFgaCheck").asBoolean())
                    .as("어긋남: %s", path.get("orgCode").asText()).isTrue();
            assertThat(path.get("cycle").asBoolean()).isFalse();
        });

        // 직속 하나 + 조상 전부. 하네스 ④가 Check 로 확인한 것과 같은 집합이어야 한다
        assertThat(경로조직).isEqualTo(기대.기대소속(직원));
        assertThat(경로조직).contains(기대.landmarks().회사());
    }

    @Test
    @DisplayName("겸직 직원은 두 갈래 경로가 모두 나온다 — 한쪽만 나오면 화면이 거짓말을 한다")
    void 겸직직원의_경로가_두_갈래다() {
        // given
        한번만_동기화한다();
        String 겸직 = 기대.landmarks().겸직직원();

        // when
        JsonNode detail = 조회한다("/admin/employees/" + 겸직);

        // then
        Set<String> 경로조직 = new LinkedHashSet<>();
        detail.get("paths").forEach(path -> 경로조직.add(path.get("orgCode").asText()));
        assertThat(경로조직).isEqualTo(기대.기대소속(겸직));

        long 직속수 = 0;
        for (JsonNode path : detail.get("paths")) {
            if ("direct".equals(path.get("via").asText())) {
                직속수++;
            }
        }
        assertThat(직속수).as("겸직이므로 직속 경로가 둘이어야 한다").isEqualTo(2);
    }

    @Test
    @DisplayName("조직 상세가 상위 계층과 직속 하위 조직을 조직도대로 보여준다")
    void 조직상세가_계층을_보여준다() {
        // given
        한번만_동기화한다();
        String 조직 = 기대.landmarks().이동할팀();

        // when
        JsonNode detail = 조회한다("/admin/organizations/" + 조직);

        // then
        List<String> 조상 = new ArrayList<>();
        detail.get("ancestors").forEach(node -> 조상.add(node.get("orgCode").asText()));
        assertThat(조상).as("가까운 순서까지 조직도와 같아야 한다").isEqualTo(기대.조상들(조직));

        Set<String> 자식 = new LinkedHashSet<>();
        detail.get("childOrganizations").forEach(node -> 자식.add(node.get("orgCode").asText()));
        assertThat(자식).isEqualTo(기대.자식조직들(조직));
    }

    @Test
    @DisplayName("5,000명 중에서 직원 검색이 답을 준다 — 커서가 있으면 끝까지 따라간다")
    void 직원검색이_규모에서_동작한다() {
        // given
        한번만_동기화한다();
        String 직원 = 기대.landmarks().L5직속직원();
        String 표시명 = 기대.snapshot().users().get(직원).displayName();

        // when — 계정명(GSI1)과 표시명(GSI2) 두 인덱스를 모두 태운다
        JsonNode byUserName = 조회한다("/admin/employees?userName=" + 직원 + "&limit=" + 최대limit);
        JsonNode byDisplayName = 조회한다("/admin/employees?displayName={prefix}&limit=" + 최대limit, 표시명);

        // then
        assertThat(아이디들(byUserName)).contains(직원);
        assertThat(아이디들(byDisplayName)).contains(직원);
    }

    @Test
    @DisplayName("표시명 접두사 하나로 5,000명이 걸리면 커서로 끝까지 따라갈 수 있다")
    void 표시명_검색이_끝까지_페이징된다() {
        // given — 전원의 표시명이 "직원 " 으로 시작한다
        한번만_동기화한다();

        // when
        Set<String> 읽은것 = new LinkedHashSet<>();
        int 중복없이 = 0;
        String cursor = null;
        int 페이지수 = 0;
        do {
            JsonNode page = cursor == null
                    ? 조회한다("/admin/employees?displayName={p}&limit=" + 최대limit, "직원")
                    : 조회한다("/admin/employees?displayName={p}&limit=" + 최대limit + "&cursor={c}",
                            "직원", cursor);
            for (JsonNode item : page.get("items")) {
                읽은것.add(item.get("employeeId").asText());
                중복없이++;
            }
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
            페이지수++;
        } while (cursor != null && 페이지수 < 200);

        // then — 5,024명 전부, 중복 없이
        assertThat(페이지수).isGreaterThan(1);
        assertThat(읽은것).hasSize(중복없이);
        assertThat(읽은것).isEqualTo(기대.snapshot().users().keySet());
    }

    @Test
    @DisplayName("없는 직원과 없는 조직은 404 — 규모가 커도 빈 결과를 200 으로 주지 않는다")
    void 없는것은_404다() {
        // given
        한번만_동기화한다();

        // when, then
        client.get().uri("/admin/employees/없는사람").exchange().expectStatus().isNotFound();
        client.get().uri("/admin/organizations/NO_SUCH_ORG").exchange().expectStatus().isNotFound();
        client.get().uri("/admin/organizations/NO_SUCH_ORG/members").exchange()
                .expectStatus().isNotFound();
    }

    // ---------- 거들기 ----------

    /**
     * 값은 <b>URI 템플릿 변수로</b> 넘긴다. 직접 인코딩해 문자열에 이어 붙이면
     * {@code WebTestClient.uri(String)} 이 그 문자열을 다시 템플릿으로 보고 {@code %} 를
     * 한 번 더 인코딩해, 한글 접두사가 {@code %25EC...} 로 도착한다 — 검색은 조용히
     * 빈 결과를 준다.
     */
    private JsonNode 조회한다(String uriTemplate, Object... values) {
        return client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .get().uri(uriTemplate, values).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
    }

    private static Set<String> 아이디들(JsonNode page) {
        Set<String> ids = new LinkedHashSet<>();
        page.get("items").forEach(item -> ids.add(item.get("employeeId").asText()));
        return ids;
    }
}

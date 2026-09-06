package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartEditor;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.ldap.fixture.LdapDirectory;
import dev.starryeye.organization.ldap.fixture.LdifRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 삭제 가드 시나리오 L12·L13 (시나리오 문서 §3).
 *
 * <p>LDAP 이 부분 응답을 돌려주는 상황 — 필터가 망가졌거나 서버가 일부만 준 경우 — 을 직원
 * 엔트리를 실제로 지워서 만든다. 가드가 없으면 그 부분 응답이 <b>대량 퇴사</b>로 보여
 * 전직원 권한이 한 번에 날아간다.
 *
 * <p><b>경계값을 둘 다 돌린다.</b> 가드는 {@code ratio <= thresholdRatio} 로 판정하므로 한 건
 * 차이로 판정이 갈린다. 통과 쪽만 보면 가드가 아예 꺼져 있어도 통과하고, 중단 쪽만 보면
 * 가드가 항상 켜져 있어도 통과한다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapDeletionGuardScaleTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final OrgChart 최초 = OrgChartFixture.오천명();
    private static final double 임계비율 = 0.3;

    private static OrgChart 기대 = 최초;
    /** 소속이 하나뿐인 직원들. 한 명 지우면 튜플이 정확히 하나 줄어 계산이 어긋나지 않는다. */
    private static List<String> 단일소속직원;

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
    static LdapDirectory 디렉터리;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("guard", 0));
        config.setSchema(null);

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                new LdifRenderer(BASE_DN).render(최초).getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();
        디렉터리 = new LdapDirectory(LDAP, BASE_DN);

        단일소속직원 = 최초.snapshot().users().keySet().stream()
                .filter(id -> 최초.직속조직들(id).size() == 1)
                .sorted()
                .toList();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;

    @Test
    @Order(1)
    @DisplayName("기준선을 만든다 — 5,541 튜플")
    void 기준선을_만든다() {
        // when, then
        동기화한다(false).jsonPath("$.writtenCount").isEqualTo(5_541);
        검증한다();
    }

    @Test
    @Order(2)
    @DisplayName("L12-a. 임계치와 같은 비율은 통과한다 — 한 건 차이의 아래쪽")
    void L12a_경계_아래는_통과한다() {
        // given — 기준선 5,541 의 30% 는 1,662.3 이므로 1,662 건이 통과 쪽 경계다
        int 기준선 = 5_541;
        int 지울건수 = (int) Math.floor(기준선 * 임계비율);
        assertThat((double) 지울건수 / 기준선).isLessThanOrEqualTo(임계비율);

        // when
        양쪽에서_지운다(0, 지울건수);

        // then
        동기화한다(false)
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.deletedCount").isEqualTo(지울건수);
        검증한다();
    }

    @Test
    @Order(3)
    @DisplayName("L12-b. 임계치를 한 건 넘기면 중단하고 아무것도 안 지운다")
    void L12b_경계_위는_중단한다() {
        // given — 새 기준선(3,879)의 30% 를 한 건 넘긴다
        int 기준선 = 5_541 - (int) Math.floor(5_541 * 임계비율);
        int 지울건수 = (int) Math.floor(기준선 * 임계비율) + 1;
        assertThat((double) 지울건수 / 기준선).isGreaterThan(임계비율);

        // LDAP 에서만 지운다. 중단되면 기대값은 그대로여야 하므로 편집하지 않는다 —
        // 여기서 기대값을 같이 옮기면 "아무것도 안 지웠다" 를 검증할 기준이 사라진다.
        int 이미지운수 = (int) Math.floor(5_541 * 임계비율);
        LDAP에서만_지운다(이미지운수, 이미지운수 + 지울건수);

        // when
        동기화한다(false)
                .jsonPath("$.status").isEqualTo("ABORTED")
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).contains("임계치").contains("force=true"));

        // then — OpenFGA 도 DynamoDB 도 손대지 않았어야 한다.
        // 가드가 "중단" 이라고 말해 놓고 절반쯤 지웠으면 그게 최악이다
        검증한다();
    }

    @Test
    @Order(4)
    @DisplayName("L13. force=true 는 가드를 지나 실제로 지운다")
    void L13_강제로_우회한다() {
        // given — LDAP 은 이미 L12-b 의 상태다. 기대값만 그쪽으로 맞춘다
        int 이미지운수 = (int) Math.floor(5_541 * 임계비율);
        int 추가로지운수 = (int) Math.floor((5_541 - 이미지운수) * 임계비율) + 1;
        var editor = OrgChartEditor.편집한다(기대);
        단일소속직원.subList(이미지운수, 이미지운수 + 추가로지운수).forEach(editor::직원을_지운다);
        기대 = editor.완성();

        // when
        동기화한다(true)
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("FORCED")
                .jsonPath("$.deletedCount").isEqualTo(추가로지운수);

        // then
        검증한다();
        String 지워진사람 = 단일소속직원.get(이미지운수);
        assertThat(성립하는가(RelationTuple.member(지워진사람, 기대.landmarks().회사()))).isFalse();
    }

    // ---------- 거들기 ----------

    private void 양쪽에서_지운다(int from, int toExclusive) {
        var editor = OrgChartEditor.편집한다(기대);
        for (String id : 단일소속직원.subList(from, toExclusive)) {
            디렉터리.직원을_지운다(id, 기대.직속조직(id));
            editor.직원을_지운다(id);
        }
        기대 = editor.완성();
    }

    /** 부분 응답을 흉내 낸다 — LDAP 만 줄어들고 우리 기대는 그대로다. */
    private void LDAP에서만_지운다(int from, int toExclusive) {
        for (String id : 단일소속직원.subList(from, toExclusive)) {
            디렉터리.직원을_지운다(id, 기대.직속조직(id));
        }
    }

    private WebTestClient.BodyContentSpec 동기화한다(boolean force) {
        return client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                .post().uri("/admin/sync/full" + (force ? "?force=true" : "")).exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    private void 검증한다() {
        var 결과 = new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10));
        assertThat(결과).isNotNull();
        assertThat(결과.어긋났는가()).as(결과 == null ? "" : 결과.요약()).isFalse();
    }

    private boolean 성립하는가(RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }
}

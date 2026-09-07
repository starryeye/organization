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
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.ldap.fixture.DitLdifRenderer;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIT 전략을 규모에서 검증한다 — 계층이 {@code member} 속성이 아니라 <b>dn 경로</b>로
 * 표현된 디렉터리를 읽는 쪽이다.
 *
 * <p><b>왜 따로 도는가.</b> 두 전략은 전혀 다른 방식으로 읽어 같은 스냅샷을 만들기로 돼
 * 있다. groupOfNames 만 규모에서 돌리고 DIT 은 단위 테스트로 남겨두면, 6단 깊이 5,000명에서
 * dn 경로 되짚기·페이징·계층 조립이 실제로 맞는지는 아무도 확인하지 않은 채가 된다.
 *
 * <p><b>겸직은 빠진다.</b> DIT 은 직원 엔트리를 ou 하나 아래에만 놓을 수 있어 형식 자체가
 * 겸직을 표현하지 못한다. 기대 조직도에서 먼저 풀고 시작한다 — 이것은 구현의 한계가 아니라
 * 형식의 한계이고, 두 전략이 담을 수 있는 범위가 다르다는 사실 자체가 결과다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DitScaleSyncTest {

    private static final String BASE_DN = "dc=example,dc=com";

    /** groupOfNames 쪽과 같은 조직도. 겸직만 풀었다. */
    private static final OrgChart 기대 = OrgChartEditor.편집한다(OrgChartFixture.오천명())
            .겸직을_모두_푼다()
            .완성();

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

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        DitLdifRenderer renderer = new DitLdifRenderer(BASE_DN, 기대.landmarks().회사());

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("dit", 0));
        config.setSchema(null);

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                renderer.render(기대).getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("ldap.strategy", () -> "dit");
        registry.add("ldap.dit.root-dn", renderer::rootDn);
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
    @DisplayName("DIT 로 심은 5,024명이 groupOfNames 와 같은 스냅샷·같은 튜플에 도달한다")
    void DIT가_같은_결과에_도달한다() {
        // given — 겸직 166건이 빠진 만큼만 튜플이 적다
        int 기대튜플 = TupleMapper.toTuples(기대.snapshot()).tuples().size();

        // when
        동기화한다().jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(기대튜플);

        // then — 같은 하네스로 잰다. 두 전략을 다른 잣대로 재면 비교가 성립하지 않는다
        검증한다();
    }

    @Test
    @Order(2)
    @DisplayName("dn 경로로 표현된 6단 계층이 롤업으로 이어진다 — 가장 깊은 직원이 회사까지")
    void 깊은_계층이_롤업된다() {
        // given
        String 깊은직원 = 기대.landmarks().L6직속직원();
        String 얕은직원 = 기대.landmarks().L2직속직원();

        // then — 위로는 닿고
        기대.기대소속(깊은직원).forEach(org ->
                assertThat(성립하는가(RelationTuple.member(깊은직원, org)))
                        .as("dn 경로 되짚기가 %s 에서 끊겼다", org).isTrue());

        // 아래로는 안 샌다. DIT 은 계층을 dn 으로만 표현하므로 경로 되짚기가 뒤집히면
        // 정확히 여기서 터진다
        기대.자손들(기대.직속조직(얕은직원)).forEach(org ->
                assertThat(성립하는가(RelationTuple.member(얕은직원, org)))
                        .as("권한이 %s 로 샜다", org).isFalse());
    }

    @Test
    @Order(3)
    @DisplayName("DIT 도 무변경 재동기화에서 OpenFGA 를 호출하지 않는다")
    void 무변경이면_호출하지_않는다() {
        // when, then
        동기화한다()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");
        검증한다();
    }

    // ---------- 거들기 ----------

    private WebTestClient.BodyContentSpec 동기화한다() {
        return client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                .post().uri("/admin/sync/full").exchange()
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

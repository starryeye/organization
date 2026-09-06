package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.SyncVerifier;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LDAP 전체 동기화 한 회차의 실비를 재고, 하네스 검증까지 통과하는지 본다.
 *
 * <p>SCIM 쪽({@code ScimScaleSyncCostTest})과 <b>같은 조직도, 같은 하네스</b>다. 두 커넥터를
 * 서로 다른 잣대로 재면 "같은 입력으로 같은 결과에 도달하는가" 를 물을 수 없다. 형식만
 * 다르고 도달점이 같아야 한다는 것이 이 설계의 전제다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapScaleSyncCostTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final OrgChart CHART = OrgChartFixture.오천명();

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
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("scale", 0));
        // 빈 조직은 groupOfNames 의 member 필수 제약에 걸린다. 그 형태를 일부러 살린다.
        config.setSchema(null);

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                new LdifRenderer(BASE_DN).render(CHART).getBytes(StandardCharsets.UTF_8))));
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

    @Test
    @DisplayName("5,024명 전체 동기화가 한 회차로 끝나고, 하네스 검증까지 통과한다")
    void 전체동기화_실비를_잰다() {
        // when
        long t0 = System.currentTimeMillis();
        client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                .post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED");
        long 싱크 = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        var 검증 = new SyncVerifier(state, checker).검증한다(CHART).block(Duration.ofMinutes(10));
        long 검증시간 = System.currentTimeMillis() - t1;

        System.out.printf("%n=== LDAP 전체 동기화 실비 ===%n동기화 %.1f초 / 검증 %.1f초%n",
                싱크 / 1000.0, 검증시간 / 1000.0);

        // then
        assertThat(검증).isNotNull();
        assertThat(검증.어긋났는가()).as(검증 == null ? "" : 검증.요약()).isFalse();
    }
}

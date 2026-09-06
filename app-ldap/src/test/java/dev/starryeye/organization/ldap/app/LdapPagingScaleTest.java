package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
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
 * 시나리오 L14 — 서버측 엔트리 상한이 걸린 디렉터리를 페이징 없이 읽으면 어떻게 되는가.
 *
 * <p><b>이것이 이 프로젝트에서 가장 중요한 방어선 중 하나다.</b> 서버가 상한만큼만 주고
 * 침묵하면(Active Directory 의 {@code MaxPageSize} 가 정확히 그렇다) 잘린 목록이 <b>대량
 * 퇴사처럼</b> 보인다. 그 상태로 진행하면 멀쩡한 직원들의 소속이 지워진다.
 *
 * <p>여기서는 {@code page-size: 0} — 페이징을 끄고 — 상한 1,000 인 서버를 읽는다. 기대하는
 * 것은 <b>실패</b>다. 조용히 1,000명만 읽고 성공했다고 말하는 것이 최악이다.
 *
 * <p>정상 페이징(11 페이지)은 L1 이 매번 밟고 있다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapPagingScaleTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final OrgChart 최초 = OrgChartFixture.오천명();
    private static final int 서버상한 = 1_000;

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
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("paging", 0));
        config.setSchema(null);
        // 서버가 한 번에 이만큼만 준다. 5,024명 중 1,000명이다.
        config.setMaxSizeLimit(서버상한);

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                new LdifRenderer(BASE_DN).render(최초).getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        // 페이징을 끈다 — 0 이하면 단일 검색으로 처리한다
        registry.add("ldap.page-size", () -> 0);
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;

    @Test
    @DisplayName("L14. 페이징 없이 상한에 걸리면 조용히 잘리지 않고 실패한다")
    void L14_잘린_목록으로_진행하지_않는다() {
        // when
        client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                .post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FAILED")
                // 사유까지 본다 — 다른 이유로 실패해도 통과하는 테스트는 이 방어선을 안 지킨다
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).containsIgnoringCase("size"));

        // then — 아무것도 쓰지 않았어야 한다.
        // 1,000명만 읽고 나머지 4,024명을 퇴사로 판정해 지우는 것이 이 방어선이 막는 일이다.
        var 상태 = state.loadAll().block(Duration.ofMinutes(1));
        assertThat(상태).isNotNull();
        assertThat(상태.users())
                .as("실패했는데 부분 상태가 남으면 다음 회차의 기준선이 오염된다")
                .isEmpty();
    }
}

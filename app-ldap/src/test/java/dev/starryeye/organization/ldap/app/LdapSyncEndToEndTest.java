package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LDAP → 도메인 → 튜플 → OpenFGA / DynamoDB 전 구간이 실제 컨테이너 위에서 이어지는지 확인한다.
 *
 * <p>개별 단위 테스트는 각 모듈을 고립시켜 검증하지만, 결선이 틀리면 전체가 동작하지 않는다.
 * 이 테스트는 관리 API(HTTP)를 통해서만 시스템을 구동해 실제 사용 경로를 그대로 재현한다.
 *
 * <p>테스트는 순서에 의존한다({@link Order}). 첫 동기화가 상태를 채우고, 두 번째가 "변경 없음"을
 * 확인하고, 이후 재적재가 그 위에서 동작한다 — 서로 독립적으로 만들면 이 테스트의 요점이 사라진다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapSyncEndToEndTest {

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

    static final String LDIF = """
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

            dn: uid=kim,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: kim
            cn: Kim Chulsoo
            sn: Kim
            displayName: 김철수
            mail: kim@example.com

            dn: uid=park,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: park
            cn: Park Minsu
            sn: Park
            displayName: 박민수
            mail: park@example.com

            dn: cn=DEV001,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV001
            description: 개발본부
            member: cn=DEV002,ou=groups,dc=example,dc=com
            member: uid=park,ou=people,dc=example,dc=com

            dn: cn=DEV002,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV002
            description: 백엔드팀
            member: uid=kim,ou=people,dc=example,dc=com
            """;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("e2e", 0));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(LDIF.getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired TupleSnapshotRepository snapshots;
    @Autowired DirectoryStateRepository state;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("수동 동기화 한 번으로 LDAP 조직도가 OpenFGA 튜플과 DynamoDB 에 모두 반영된다")
    void 전_구간이_한_번에_이어진다() {
        // given, when
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — OpenFGA 에 롤업이 성립한다
        assertThat(check("user:kim", "member", "group:DEV002")).isTrue();
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
        assertThat(check("user:park", "member", "group:DEV002")).isFalse();

        // then — DynamoDB 에 현재상태가 남는다
        var loaded = state.loadAll().block();
        assertThat(loaded.users()).containsOnlyKeys("kim", "park");
        assertThat(loaded.groups().get("DEV001").displayName()).isEqualTo("개발본부");

        // then — 스냅샷이 남는다
        var snapshot = snapshots.findLatest().block();
        assertThat(snapshot.tuples()).hasSize(3);
    }

    @Test
    @Order(2)
    @DisplayName("변경이 없는 상태에서 다시 동기화하면 아무것도 쓰지 않는다")
    void 재실행하면_변경_없음으로_끝난다() {
        // given, when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");
    }

    @Test
    @Order(3)
    @DisplayName("snapshot 모드 재적재 후에도 롤업이 그대로 성립한다")
    void snapshot_모드_재적재가_동작한다() {
        // given, when
        client.post().uri("/admin/sync/rebuild?mode=snapshot").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("REBUILD");

        // then
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        assertThat(snapshots.findLatest().block().tuples()).hasSize(3);
    }

    @Test
    @Order(4)
    @DisplayName("store 모드 재적재는 store 를 비우고 다시 채운다")
    void store_모드_재적재가_동작한다() {
        // given, when
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("실행 이력에 지금까지의 동기화가 최신순으로 남아 있다")
    void 실행_이력이_남는다() {
        // given, when, then
        client.get().uri("/admin/sync/runs?limit=10").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").value(len -> assertThat((Integer) len).isGreaterThanOrEqualTo(4))
                .jsonPath("$[0].source").isEqualTo("LDAP");
    }

    @Test
    @Order(6)
    @DisplayName("헬스체크가 DynamoDB 와 OpenFGA 연결을 모두 UP 으로 보고한다")
    void 헬스체크가_UP이다() {
        // given, when, then
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.dynamoDb.status").isEqualTo("UP")
                .jsonPath("$.components.openFga.status").isEqualTo("UP");
    }
}

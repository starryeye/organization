package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.SyncRunRepository;
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
    @Autowired SyncRunRepository runs;

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

    /** 스냅샷에 없는 튜플을 OpenFGA 에 직접 심는다. 동기화 경로를 거치지 않으므로 상태에도 없다. */
    private void 잔여튜플을_심는다(String user, String relation, String object) {
        try {
            bootstrapper.client().write(
                    new ClientWriteRequest().writes(java.util.List.of(new ClientTupleKey()
                            .user(user).relation(relation)._object(object)))).get();
        } catch (Exception e) {
            throw new IllegalStateException("잔여 튜플 심기 실패", e);
        }
    }

    @Test
    @Order(4)
    @DisplayName("snapshot 모드 재적재는 스냅샷에 없던 잔여 튜플을 지우지 못한다 — 알려진 한계 (설계 §14.2)")
    void snapshot_모드는_잔여_튜플을_남긴다() {
        // given — 어긋남을 흉내낸다. 예컨대 과거의 부분 실패로 남은 튜플,
        // 혹은 사람이 손으로 넣은 튜플이 이런 모습이다.
        잔여튜플을_심는다("user:ghost", "direct_member", "group:DEV001");
        assertThat(check("user:ghost", "member", "group:DEV001")).isTrue();

        // when — 스냅샷 기준 재적재
        client.post().uri("/admin/sync/rebuild?mode=snapshot").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 여전히 남아 있다. snapshot 모드는 "스냅샷이 요구하는 것을 다시 쓴다" 이지
        // "스냅샷에 없는 것을 지운다" 가 아니다. 스냅샷에 애초에 없는 튜플은
        // 지울 대상으로 인식되지 않는다 — 고치려는 바로 그 상황에서 듣지 않는 이유다.
        assertThat(check("user:ghost", "member", "group:DEV001"))
                .as("설계 §14.2 가 명시적 검증을 요구한 한계. 이것이 false 가 되면 한계가 해소된 것이니 문서를 고칠 것")
                .isTrue();
        // 정상 튜플은 그대로다
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("store 모드 재적재는 store 를 비우고 다시 채운다")
    void store_모드_재적재가_동작한다() {
        // given, when
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        // snapshot 모드가 못 지운 잔여 튜플은 store 모드가 쓸어낸다.
        // 어긋남을 실제로 고치려면 이쪽이어야 한다는 뜻이다.
        assertThat(check("user:ghost", "member", "group:DEV001")).isFalse();

        // and — 개수까지 확인한다. Check 만 보면 "필요한 것이 있다" 는 알 수 있어도
        // "필요 없는 것이 없다" 는 모른다. 조직도가 요구하는 튜플은 정확히 3개다
        // (kim→DEV002, park→DEV001, DEV002→DEV001).
        assertThat(snapshots.findLatest().block().tuples())
                .as("재적재 뒤 스냅샷은 조직도가 요구하는 것만 담아야 한다")
                .hasSize(3);
    }

    @Test
    @Order(6)
    @DisplayName("실행 이력에 지금까지의 동기화가 최신순으로 남아 있다")
    void 실행_이력이_남는다() {
        // given, when, then
        client.get().uri("/admin/sync/runs?limit=10").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").value(len -> assertThat((Integer) len).isGreaterThanOrEqualTo(4))
                .jsonPath("$[0].source").isEqualTo("LDAP");

        // and — 트리거 종류가 실제로 갈려 기록되는지 본다. 전부 MANUAL 로 뭉개져도
        // 위 단언은 통과하는데, 그러면 "누가 이 동기화를 시작했나" 를 이력에서 알 수 없다.
        var 트리거들 = runs.findRecent(10).collectList().block().stream()
                .map(run -> run.trigger().name())
                .toList();
        assertThat(트리거들)
                .as("수동 동기화(MANUAL)와 재적재(REBUILD)가 서로 다른 트리거로 남아야 한다")
                .contains("MANUAL", "REBUILD");
    }

    @Test
    @Order(7)
    @DisplayName("헬스체크가 LDAP·DynamoDB·OpenFGA 세 의존성을 모두 UP 으로 보고한다")
    void 헬스체크가_UP이다() {
        // given, when, then — 설계 §12.3 이 요구하는 셋을 모두 확인한다.
        // 전에는 둘만 단언해, LDAP 인디케이터가 아예 없다는 사실을 이 테스트가 덮고 있었다.
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.ldap.status").isEqualTo("UP")
                .jsonPath("$.components.dynamoDb.status").isEqualTo("UP")
                .jsonPath("$.components.openFga.status").isEqualTo("UP");
    }
}

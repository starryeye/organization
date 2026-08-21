package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.starryeye.organization.authz.StoreBootstrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * SCIM 재적재가 실제 인프라 위에서 동작하는지 확인한다.
 *
 * <p>이 스위트의 핵심은 {@link #튜플_재적재가_어긋남을_복구한다()} 다. 조회 API 가 어긋남을
 * 드러내는 것까지는 이전 사이클에서 확인했고, 여기서는 <b>그걸 실제로 고칠 수 있는지</b>를 본다.
 *
 * <p>순서에 의존한다({@link Order}) — 앞 테스트가 만든 조직도 위에서 뒤 테스트가 어긋남을
 * 만들고 복구하고, 마지막에 전부 비운다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimRebuildEndToEndTest {

    /** {@code application-test.yml} 의 {@code dynamodb.table-name} 과 같아야 한다 */
    private static final String TABLE_NAME = "organization-scim-e2e";

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

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;

    private void 조직도를_만든다() {
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"gd.hong","displayName":"홍길동","active":true}""")
                .exchange().expectStatus().isCreated();
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV002","displayName":"백엔드팀",
                         "members":[{"value":"gd.hong"}]}""")
                .exchange().expectStatus().isCreated();
    }

    @Test
    @Order(1)
    @DisplayName("튜플 재적재가 직접 지운 튜플을 복구한다")
    void 튜플_재적재가_어긋남을_복구한다() throws Exception {
        // given — 조직도를 만든 뒤 OpenFGA 에서 튜플만 직접 지워 어긋나게 만든다
        조직도를_만든다();
        bootstrapper.client().deleteTuples(List.of(
                new ClientTupleKeyWithoutCondition()
                        .user("user:gd.hong").relation("direct_member")._object("group:DEV002"))).get();

        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(false);

        // when
        client.post().uri("/admin/sync/rebuild?mode=tuples")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("REBUILD")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 어긋남이 사라졌다. 이것이 이 기능의 존재 이유다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(true);
    }

    @Test
    @Order(2)
    @DisplayName("튜플 재적재는 조직도를 건드리지 않는다")
    void 튜플_재적재는_조직도를_남긴다() {
        // when, then — 상태가 곧 진실이므로 재적재가 그것을 지우면 안 된다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("홍길동");
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("백엔드팀");
    }

    @Test
    @Order(3)
    @DisplayName("재적재는 SCIM 이력에 남는다")
    void 이력에_남는다() {
        // when, then
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].source").isEqualTo("SCIM")
                .jsonPath("$[0].trigger").isEqualTo("REBUILD");
    }

    @Test
    @Order(4)
    @DisplayName("wipe 는 confirm 이 테이블명과 다르면 400 이고 아무것도 지우지 않는다")
    void confirm이_틀리면_400이다() {
        // when, then — 불리언 플래그였다면 손가락이 미끄러져 조직도가 날아갔을 자리다
        client.post().uri("/admin/sync/rebuild?mode=wipe")
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/sync/rebuild?mode=wipe&confirm=아무거나")
                .exchange().expectStatus().isBadRequest();

        // 조직도는 그대로다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk();
    }

    @Test
    @Order(5)
    @DisplayName("알 수 없는 mode 는 400 이다")
    void 알수없는_모드는_400이다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=nuke")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @Order(6)
    @DisplayName("wipe 는 조직도를 전부 비우고 감사 이력은 남긴다")
    void wipe가_조직도를_비운다() {
        // when — 테이블명을 그대로 적어야만 실행된다
        client.post().uri("/admin/sync/rebuild?mode=wipe&confirm=" + TABLE_NAME)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("RESET")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 직원도 조직도 사라졌다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isNotFound();
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isNotFound();
        client.get().uri("/admin/employees?displayName=홍")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items").isEmpty();

        // then — 사고 뒤에 무슨 일이 있었는지 볼 기록은 남아 있다
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].trigger").isEqualTo("RESET")
                .jsonPath("$[1].trigger").isEqualTo("REBUILD");
    }

    @Test
    @Order(7)
    @DisplayName("wipe 뒤에도 SCIM 쓰기는 열려 있다 — IdP 재푸시를 받아야 하기 때문이다")
    void wipe_뒤에_쓰기가_열려있다() {
        // when — IdP 가 재프로비저닝으로 다시 밀어넣는 상황이다
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"cs.kim","displayName":"김철수","active":true}""")
                .exchange().expectStatus().isCreated();

        // then — 게이트가 샜다면 여기서 503 이 났을 것이다
        client.get().uri("/admin/employees/cs.kim")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("김철수");
    }
}

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
 * 관리자 조회 API(admin-api)가 app-scim 배선 위에서 실제로 동작하는지 확인한다.
 *
 * <p>이 스위트의 핵심은 {@link #드리프트를_드러낸다()} 다. 현재상태(DynamoDB)와 OpenFGA 의
 * 실제 튜플이 어긋날 수 있다는 것, 그리고 이 조회 API 가 그 어긋남을 드러낸다는 것이 이
 * 기능이 존재하는 유일한 이유다.
 *
 * <p>테스트는 순서에 의존한다({@link Order}). 첫 테스트가 만든 직원·조직 위에서 두 번째가
 * 튜플을 직접 지워 상태를 어긋나게 만들고, 세 번째가 그 위에 상위 조직을 더 쌓는다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminQueryEndToEndTest {

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

    @Test
    @Order(1)
    @DisplayName("SCIM 으로 만든 직원을 표시명으로 검색하고 상세에서 경로를 본다")
    void 검색하고_상세를_본다() {
        // given — SCIM 으로 직원과 조직을 만든다
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

        // when, then — 표시명 접두사 검색
        client.get().uri("/admin/employees?displayName=홍")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].employeeId").isEqualTo("gd.hong");

        // then — 상세에서 파생값과 실제 판정이 모두 true 로 일치한다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("홍길동")
                .jsonPath("$.paths[0].orgCode").isEqualTo("DEV002")
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(true);
    }

    @Test
    @Order(2)
    @DisplayName("OpenFGA 에서 튜플을 직접 지우면 조회가 어긋남을 드러낸다")
    void 드리프트를_드러낸다() throws Exception {
        // given — 이 API 의 존재 이유다. 튜플만 직접 지워 상태와 어긋나게 만든다
        bootstrapper.client().deleteTuples(List.of(
                new ClientTupleKeyWithoutCondition()
                        .user("user:gd.hong").relation("direct_member")._object("group:DEV002"))).get();

        // when, then — 상태는 그대로이므로 파생값은 true 인데 실제 판정은 false 다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(false);
    }

    @Test
    @Order(3)
    @DisplayName("조직 상세가 상위 계층과 직속 소속을 준다")
    void 조직_상세를_준다() {
        // given — 상위 조직을 만들어 계층을 만든다
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"플랫폼개발본부",
                         "members":[{"value":"DEV002","type":"Group"}]}""")
                .exchange().expectStatus().isCreated();

        // when, then
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("백엔드팀")
                .jsonPath("$.ancestors[0].orgCode").isEqualTo("DEV001")
                .jsonPath("$.members.items[0].employeeId").isEqualTo("gd.hong");
    }

    @Test
    @Order(4)
    @DisplayName("검색 파라미터 없이 부르면 400 이다")
    void 파라미터가_없으면_400이다() {
        // when, then
        client.get().uri("/admin/employees").exchange().expectStatus().isBadRequest();
    }
}

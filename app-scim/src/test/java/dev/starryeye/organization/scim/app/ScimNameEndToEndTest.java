package dev.starryeye.organization.scim.app;

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

/**
 * SCIM 이름(RFC 7643 §4.1.1 `name`)·이메일이 Okta·Entra 가 실제로 보내는 요청 모양대로
 * 실제 DynamoDB Local 위에서 저장·조회·PATCH 되는지 확인한다.
 *
 * <p>테스트는 순서에 의존한다 — 준비 단계가 만든 직원 위에서 이후 PATCH 들이 이어진다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimNameEndToEndTest {

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

    @Test
    @Order(1)
    @DisplayName("Okta 식 생성 — 성·이름을 담아 만들면 GET 과 userName 조회에 그대로 나온다")
    void Okta_식_생성() {
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"hong@example.com","name":{"givenName":"길동","familyName":"홍"},
                         "emails":[{"value":"hong@example.com","primary":true}],"active":true}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name.givenName").isEqualTo("길동")
                .jsonPath("$.name.formatted").doesNotExist();

        client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"HONG@example.com\"")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].name.familyName").isEqualTo("홍")
                .jsonPath("$.Resources[0].name.givenName").isEqualTo("길동");
    }

    @Test
    @Order(2)
    @DisplayName("Entra 식 PATCH — 이메일과 성을 한 요청에서 바꾸면 200 이고 둘 다 반영된다")
    void Entra_식_PATCH() {
        client.patch().uri("/scim/v2/Users/hong@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[
                           {"op":"Replace","path":"emails[type eq \\"work\\"].value","value":"updatedEmail@microsoft.com"},
                           {"op":"Replace","path":"name.familyName","value":"updatedFamilyName"}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/scim/v2/Users/hong@example.com")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.emails[0].value").isEqualTo("updatedEmail@microsoft.com")
                .jsonPath("$.name.familyName").isEqualTo("updatedFamilyName")
                .jsonPath("$.name.givenName").isEqualTo("길동");
    }

    @Test
    @Order(3)
    @DisplayName("저장하지 않는 속성을 경로로 PATCH 하면 400 invalidPath 이고 아무것도 바뀌지 않는다")
    void 저장하지_않는_속성은_거절한다() {
        client.patch().uri("/scim/v2/Users/hong@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[
                           {"op":"Replace","path":"name.givenName","value":"길순"},
                           {"op":"Replace","path":"title","value":"과장"}]}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidPath");

        client.get().uri("/scim/v2/Users/hong@example.com")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.name.givenName").isEqualTo("길동");
    }
}

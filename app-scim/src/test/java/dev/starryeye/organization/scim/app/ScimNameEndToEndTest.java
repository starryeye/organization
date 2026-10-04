package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fixture.Containers;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 이름(RFC 7643 §4.1.1 `name`)·이메일이 Okta·Entra 가 실제로 보내는 요청 모양대로
 * 실제 DynamoDB Local 위에서 저장·조회·PATCH 되는지 확인한다.
 *
 * <p>테스트는 순서에 의존한다 — 준비 단계가 만든 직원 위에서 이후 PATCH 들이 이어진다. 직원의 아이디는 서버가 정하므로(설계 2026-10-04 §3.1)
 * 준비 단계가 응답의 id 를 {@link #홍길동} 에 받아 둔다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimNameEndToEndTest {

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    /** 순서 1 의 POST 응답에서 받은 서버 id. 테스트 인스턴스는 메서드마다 새로 만들어지므로 정적이다. */
    private static String 홍길동;

    @Autowired WebTestClient client;

    @Test
    @Order(1)
    @DisplayName("Okta 식 생성 — 성·이름을 담아 만들면 GET 과 userName 조회에 그대로 나온다")
    void Okta_식_생성() {
        // given, when
        var 응답 = client.post().uri("/scim/v2/Users")
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
                .jsonPath("$.name.formatted").doesNotExist()
                .returnResult();
        홍길동 = 만든_아이디(응답);

        // then — 아이디는 서버가 발급한 UUID 이고 userName 은 받은 그대로다
        assertThat(UUID.fromString(홍길동)).hasToString(홍길동);
        client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"HONG@example.com\"")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].id").isEqualTo(홍길동)
                .jsonPath("$.Resources[0].userName").isEqualTo("hong@example.com")
                .jsonPath("$.Resources[0].name.familyName").isEqualTo("홍")
                .jsonPath("$.Resources[0].name.givenName").isEqualTo("길동");
    }

    @Test
    @Order(2)
    @DisplayName("Entra 식 PATCH — 이메일과 성을 한 요청에서 바꾸면 200 이고 둘 다 반영된다")
    void Entra_식_PATCH() {
        // given, when
        client.patch().uri("/scim/v2/Users/" + 홍길동)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[
                           {"op":"Replace","path":"emails[type eq \\"work\\"].value","value":"updatedEmail@microsoft.com"},
                           {"op":"Replace","path":"name.familyName","value":"updatedFamilyName"}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        // then
        client.get().uri("/scim/v2/Users/" + 홍길동)
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
        // given, when, then
        client.patch().uri("/scim/v2/Users/" + 홍길동)
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

        client.get().uri("/scim/v2/Users/" + 홍길동)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.name.givenName").isEqualTo("길동");
    }

    @Test
    @Order(4)
    @DisplayName("Entra 표준 호환 모드(aadOptscim062020) 식 PATCH — 경로 없는 값의 점 표기 키도 반영된다")
    void Entra_표준_호환_모드_식_PATCH() {
        // given, when
        client.patch().uri("/scim/v2/Users/" + 홍길동)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","value":{
                           "displayName":"새이름",
                           "name.givenName":"새길동",
                           "name.familyName":"새성",
                           "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber":"E123"}}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        // then
        client.get().uri("/scim/v2/Users/" + 홍길동)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("새이름")
                .jsonPath("$.name.givenName").isEqualTo("새길동")
                .jsonPath("$.name.familyName").isEqualTo("새성");
    }
}

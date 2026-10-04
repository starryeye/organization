package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.scim.fixture.ScimIdBook;
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

import java.util.List;
import java.util.UUID;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 목록·필터 조회(RFC 7644 §3.4.2)를 Okta·Entra 가 실제로 보내는 요청 모양대로
 * 실제 DynamoDB Local 위에서 확인한다.
 *
 * <p>테스트는 순서에 의존한다 — 준비 단계가 만든 직원·조직 위에서 이후 조회들이 이어진다. 아이디는 서버가 정하므로(설계 2026-10-04 §3.1)
 * 준비 단계가 응답의 id 를 {@link #아이디들} 에 받아 두고, 조회 결과의 id 는 거기서 꺼내 맞춘다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimQueryEndToEndTest {

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

    /** 준비 단계가 만든 리소스의 이름(kim, park, dev) → 서버 id. 테스트 인스턴스는 메서드마다 새로 만들어지므로 정적이다. */
    private static final ScimIdBook 아이디들 = new ScimIdBook();

    @Autowired WebTestClient client;

    /** 만들고, 응답의 서버 id 를 이름으로 적어 둔다. */
    private void 생성한다(String 이름, String uri, String body) {
        아이디들.기록한다(이름, 만든_아이디(client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()));
    }

    @Test
    @Order(1)
    @DisplayName("직원 둘과 조직 하나를 SCIM 으로 만든다")
    void 준비한다() {
        // given, when — 조직의 멤버 값은 IdP 가 받은 직원 id 다
        생성한다("kim", "/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"ext-kim","userName":"Kim.Lee","displayName":"이김","active":true}
                """);
        생성한다("park", "/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"ext-park","userName":"park","displayName":"박","active":true}
                """);
        생성한다("dev", "/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"grp-dev","displayName":"Dev Team",
                 "members":[{"value":"%s","type":"User"}]}
                """.formatted(아이디들.서버("kim")));

        // then — 셋 모두 서버가 발급한 UUID 를 받았다(201 은 만든_아이디가 확인한다)
        List.of("kim", "park", "dev").forEach(이름 ->
                assertThat(UUID.fromString(아이디들.서버(이름))).hasToString(아이디들.서버(이름)));
    }

    @Test
    @Order(2)
    @DisplayName("Okta — userName 필터는 대소문자를 가리지 않고 ListResponse 로 답한다")
    void Okta_userName_필터() {
        client.get().uri("/scim/v2/Users?filter={f}&startIndex=1&count=100", "userName eq \"kim.lee\"")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/scim+json")
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:ListResponse")
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.Resources[0].userName").isEqualTo("Kim.Lee");
    }

    @Test
    @Order(3)
    @DisplayName("Okta — 필터 없는 목록은 userName 소문자 순이다")
    void Okta_목록() {
        // given, when, then — 순서는 userName 소문자 기준이고, 아이디는 서버가 발급한 값이다
        client.get().uri("/scim/v2/Users?startIndex=1&count=100")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].userName").isEqualTo("Kim.Lee")
                .jsonPath("$.Resources[0].id").isEqualTo(아이디들.서버("kim"))
                .jsonPath("$.Resources[1].userName").isEqualTo("park")
                .jsonPath("$.Resources[1].id").isEqualTo(아이디들.서버("park"));
    }

    @Test
    @Order(4)
    @DisplayName("Okta — 조직명 필터는 멤버까지 담는다")
    void Okta_조직_필터() {
        // given, when, then
        client.get().uri("/scim/v2/Groups?filter={f}&startIndex=1&count=100", "displayName eq \"dev team\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(1)
                .jsonPath("$.Resources[0].members[0].value").isEqualTo(아이디들.서버("kim"));
    }

    @Test
    @Order(5)
    @DisplayName("Entra — externalId 필터는 대소문자를 가린다")
    void Entra_externalId() {
        client.get().uri("/scim/v2/Users?filter={f}", "externalId eq \"ext-kim\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(1);
        client.get().uri("/scim/v2/Users?filter={f}", "externalId eq \"EXT-KIM\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(0);
    }

    @Test
    @Order(6)
    @DisplayName("Entra — excludedAttributes=members 면 조직 목록과 단건 모두 멤버를 담지 않는다")
    void Entra_멤버_제외() {
        // given, when, then
        client.get().uri("/scim/v2/Groups?excludedAttributes=members&filter={f}", "displayName eq \"Dev Team\"")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].id").isEqualTo(아이디들.서버("dev"))
                .jsonPath("$.Resources[0].members").doesNotExist();
        client.get().uri("/scim/v2/Groups/" + 아이디들.서버("dev") + "?excludedAttributes=members")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(아이디들.서버("dev"))
                .jsonPath("$.members").doesNotExist();
    }

    @Test
    @Order(7)
    @DisplayName("대소문자만 다른 userName 으로 만들면 409 다")
    void 대소문자만_다른_userName_은_409() {
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"KIM.LEE","active":true}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");
    }

    @Test
    @Order(8)
    @DisplayName(".search 는 본문의 조회를 실행한다")
    void search() {
        client.post().uri("/scim/v2/Users/.search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:SearchRequest"],
                         "filter":"userName eq \\"park\\"","attributes":["userName"]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.Resources[0].userName").isEqualTo("park")
                .jsonPath("$.Resources[0].displayName").doesNotExist();
    }

    @Test
    @Order(9)
    @DisplayName("한 명씩 페이지를 넘기면 실제 저장소의 책갈피로 이어 읽어 각자 한 번씩 나온다")
    void 책갈피로_이어_읽는다() {
        // given, when, then — 책갈피는 userName 순서를 따라가고, 각 쪽에 나오는 id 는 서버가 발급한 값이다
        client.get().uri("/scim/v2/Users?startIndex=1&count=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].id").isEqualTo(아이디들.서버("kim"));
        client.get().uri("/scim/v2/Users?startIndex=2&count=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2)
                .jsonPath("$.Resources[0].id").isEqualTo(아이디들.서버("park"));
    }

    @Test
    @Order(10)
    @DisplayName("받지 않는 필터는 invalidFilter 이고, ServiceProviderConfig 는 필터 지원을 선언한다")
    void 필터_오류와_선언() {
        client.get().uri("/scim/v2/Users?filter={f}", "userName co \"k\"")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.scimType").isEqualTo("invalidFilter");
        client.get().uri("/scim/v2/ServiceProviderConfig")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.filter.supported").isEqualTo(true);
    }
}

package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.core.fixture.Containers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.ArrayList;
import java.util.List;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 한도(설계 2026-10-17 §3·§4)와 발견 엔드포인트(§5)를 실제 앱·컨테이너 위에서 HTTP 응답으로 고정한다.
 *
 * <p>DynamoDB Local 은 호출마다 키 길이를 다르게 다룬다 — GetItem·BatchGetItem 키와 GSI 정렬키는 거절하고, Query 의 파티션키 값은 받는다.
 * 그래서 이 시험은 "긴 값이 500 이 아니라 404·빈 목록·400 으로 나간다" 를 본다. "한도를 넘는 키로 DynamoDB 를 부르지 않는다" 는
 * 저장소 시험({@code KeyLimitReadTest})이 고정한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimKeyLimitEndToEndTest {

    private static final String 긴_값 = "x".repeat(2100);
    private static final String 긴_표시명 = "x".repeat(1025);

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

    @Autowired WebTestClient client;

    /** 직원을 만들고 서버가 발급한 id 를 돌려준다. */
    private String 직원을_만든다(String userName) {
        return 만든_아이디(client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s","displayName":"%s","active":true}
                        """.formatted(userName, userName))
                .exchange());
    }

    /** 조직을 만들고 서버가 발급한 id 를 돌려준다. 멤버는 직원의 서버 id 다. */
    private String 조직을_만든다(String displayName, String 직원아이디) {
        return 만든_아이디(client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"%s","members":[{"value":"%s","type":"User"}]}
                        """.formatted(displayName, 직원아이디))
                .exchange());
    }

    private List<String> 멤버들(String 조직아이디) {
        JsonNode group = client.get().uri("/scim/v2/Groups/" + 조직아이디).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        List<String> values = new ArrayList<>();
        if (group.has("members")) {
            group.get("members").forEach(member -> values.add(member.get("value").asText()));
        }
        return values;
    }

    private void SCIM_오류_404를_본다(WebTestClient.ResponseSpec 응답) {
        응답.expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("404");
    }

    @Test
    @DisplayName("직원·조직 경로의 아이디가 키 한도를 넘어도 500 이 아니라 404 SCIM 오류다")
    void 긴_경로_아이디는_404다() {
        // given — 경로가 Netty 시작 줄 한도를 넘지 않게 긴 아이디는 2100바이트다
        String 직원_본문 = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"long-path-kim","displayName":"긴 경로","active":true}
                """;
        String 패치_본문 = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","path":"displayName","value":"바뀜"}]}
                """;

        // when, then
        SCIM_오류_404를_본다(client.get().uri("/scim/v2/Users/" + 긴_값).exchange());
        SCIM_오류_404를_본다(client.put().uri("/scim/v2/Users/" + 긴_값)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(직원_본문).exchange());
        SCIM_오류_404를_본다(client.patch().uri("/scim/v2/Users/" + 긴_값)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(패치_본문).exchange());
        SCIM_오류_404를_본다(client.delete().uri("/scim/v2/Users/" + 긴_값).exchange());
        SCIM_오류_404를_본다(client.get().uri("/scim/v2/Groups/" + 긴_값).exchange());
    }

    @Test
    @DisplayName("키 한도를 넘는 필터 값으로 찾으면 500 이 아니라 결과가 없는 200 목록이다")
    void 긴_필터_값은_빈_목록이다() {
        // given, when, then
        client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"" + 긴_값 + "\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(0);
        client.get().uri("/scim/v2/Users?filter={f}", "id eq \"" + 긴_값 + "\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(0);
        client.get().uri("/scim/v2/Groups?filter={f}", "displayName eq \"" + 긴_값 + "\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(0);
    }

    @Test
    @DisplayName("1025바이트 표시명으로 직원을 만들면 400 invalidValue 이고 detail 에 displayName 이 있다")
    void 긴_표시명의_직원_생성은_400이다() {
        // given, when, then
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"long-display-kim","displayName":"%s","active":true}
                        """.formatted(긴_표시명))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("displayName"));
    }

    @Test
    @DisplayName("displayName 없이 name.formatted 에서 표시명이 정해져도 한도를 보고, 표시명은 그대로다")
    void 표시명이_이름에서_정해져도_한도를_본다() {
        // given
        String id = 직원을_만든다("name-derived-kim");

        // when
        client.put().uri("/scim/v2/Users/" + id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"name-derived-kim","name":{"formatted":"%s"},"active":true}
                        """.formatted(긴_표시명))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");

        // then
        client.get().uri("/scim/v2/Users/" + id).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("name-derived-kim");
    }

    @Test
    @DisplayName("1001바이트 멤버가 섞인 조직 PUT 은 400 invalidValue 이고 멤버는 그대로다")
    void 긴_멤버가_섞인_교체는_400이다() {
        // given
        String 직원 = 직원을_만든다("member-put-kim");
        String 조직 = 조직을_만든다("member-put-org", 직원);

        // when
        client.put().uri("/scim/v2/Groups/" + 조직).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"member-put-org",
                         "members":[{"value":"%s"},{"value":"%s"}]}
                        """.formatted(직원, "u".repeat(1001)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");

        // then
        assertThat(멤버들(조직)).containsExactly(직원);
    }

    @Test
    @DisplayName("한도를 넘는 멤버를 빼는 PATCH 는 400 이 아니라 204 이고 멤버는 그대로다")
    void 긴_멤버_빼기는_204다() {
        // given
        String 직원 = 직원을_만든다("member-remove-kim");
        String 조직 = 조직을_만든다("member-remove-org", 직원);

        // when
        client.patch().uri("/scim/v2/Groups/" + 조직).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}
                        """.formatted(긴_값))
                .exchange()
                .expectStatus().isNoContent();

        // then
        assertThat(멤버들(조직)).containsExactly(직원);
    }

    @Test
    @DisplayName("관리 API 도 긴 아이디·값을 500 이 아니라 없음으로 답한다 — 직원 상세 404, 검색·이력은 빈 목록")
    void 관리_API_의_긴_값은_없음이다() {
        // given, when, then
        client.get().uri("/admin/employees/" + 긴_값).exchange()
                .expectStatus().isNotFound();
        client.get().uri("/admin/employees?userName={v}", 긴_표시명).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items").isEmpty();
        client.get().uri("/admin/employees/" + 긴_값 + "/history").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items").isEmpty();
    }

    @Test
    @DisplayName("앱에서 /Schemas 와 /ResourceTypes/User 가 열려 있다")
    void 발견_엔드포인트를_앱에서_연다() {
        // given, when, then
        client.get().uri("/scim/v2/Schemas").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalResults").isEqualTo(2);
        client.get().uri("/scim/v2/ResourceTypes/User").exchange()
                .expectStatus().isOk();
    }
}

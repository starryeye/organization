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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 응답의 {@code meta.created}·{@code meta.lastModified} 를 실제 컨테이너 위에서 본다(설계 2026-10-09 §7). 시각은 서버 시계라 값이 아니라
 * 같은가·뒤인가로 본다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimMetaTimesEndToEndTest {

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

    private JsonNode 보낸다(WebTestClient.RequestHeadersSpec<?> request, int status) {
        return request.exchange().expectStatus().isEqualTo(status)
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private JsonNode 직원을_만든다(String userName) {
        return 보낸다(client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s","displayName":"%s","active":true}
                        """.formatted(userName, userName)), 201);
    }

    private JsonNode 조직을_만든다(String name, String membersJson) {
        return 보낸다(client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"%s","members":[%s]}
                        """.formatted(name, membersJson)), 201);
    }

    /** URI 템플릿과 변수 — 필터 값의 따옴표·공백은 변수로 넘겨 인코딩을 WebTestClient 에 맡긴다(ScimQueryEndToEndTest 와 같다). */
    private JsonNode 읽는다(String uriTemplate, Object... variables) {
        return 보낸다(client.get().uri(uriTemplate, variables), 200);
    }

    private WebTestClient.ResponseSpec 패치(String path, String operations) {
        return client.patch().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}
                        """.formatted(operations))
                .exchange();
    }

    private static Instant created(JsonNode resource) {
        return Instant.parse(resource.get("meta").get("created").asText());
    }

    private static Instant lastModified(JsonNode resource) {
        return Instant.parse(resource.get("meta").get("lastModified").asText());
    }

    @Test
    @DisplayName("직원: 만들면 두 시각이 같고, 같은 값 PATCH 는 그대로, 바꾸는 PATCH·PUT 은 변경 시각만 움직인다")
    void 직원의_두_시각() {
        // given
        JsonNode 만든 = 직원을_만든다("meta-kim");
        String id = 만든.get("id").asText();
        String 경로 = "/scim/v2/Users/" + id;

        // then — 만들면 같다, GET 도 같은 값이다
        assertThat(created(만든)).isEqualTo(lastModified(만든));
        assertThat(lastModified(읽는다(경로))).isEqualTo(lastModified(만든));

        // when — 같은 값
        JsonNode 같은값 = 패치(경로, """
                [{"op":"replace","path":"displayName","value":"meta-kim"}]
                """).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        // then
        assertThat(lastModified(같은값)).isEqualTo(lastModified(만든));

        // when — 바꾸는 PATCH
        JsonNode 바꾼 = 패치(경로, """
                [{"op":"replace","path":"displayName","value":"김메타"}]
                """).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        // then — 응답과 GET 이 같은 값이고, 생성 시각은 그대로다
        assertThat(lastModified(바꾼)).isAfter(lastModified(만든));
        assertThat(created(바꾼)).isEqualTo(created(만든));
        assertThat(lastModified(읽는다(경로))).isEqualTo(lastModified(바꾼));

        // when — PUT
        JsonNode 교체 = 보낸다(client.put().uri(경로).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"meta-kim","displayName":"김메타2","active":true}
                        """), 200);

        // then
        assertThat(lastModified(교체)).isAfter(lastModified(바꾼));
        assertThat(created(교체)).isEqualTo(created(만든));
    }

    @Test
    @DisplayName("조직: 멤버만 넣고 빼도 변경 시각이 움직이고, 멤버를 흘려 쓰는 GET 과 빼는 GET 이 같은 값이다")
    void 조직의_두_시각() {
        // given
        String 직원 = 직원을_만든다("meta-member").get("id").asText();
        JsonNode 만든 = 조직을_만든다("메타팀", "");
        String 경로 = "/scim/v2/Groups/" + 만든.get("id").asText();
        assertThat(created(만든)).isEqualTo(lastModified(만든));

        // when — 멤버 넣기
        패치(경로, """
                [{"op":"add","path":"members","value":[{"value":"%s","type":"User"}]}]
                """.formatted(직원)).expectStatus().isNoContent();
        JsonNode 넣은뒤 = 읽는다(경로);

        // then
        assertThat(lastModified(넣은뒤)).isAfter(lastModified(만든));
        assertThat(created(넣은뒤)).isEqualTo(created(만든));
        assertThat(lastModified(읽는다(경로 + "?excludedAttributes=members"))).isEqualTo(lastModified(넣은뒤));

        // when — 멤버 빼기
        패치(경로, """
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]
                """.formatted(직원)).expectStatus().isNoContent();

        // then
        assertThat(lastModified(읽는다(경로))).isAfter(lastModified(넣은뒤));
    }

    @Test
    @DisplayName("하위 조직을 지우면 상위 조직의 변경 시각이 움직이고 생성 시각은 그대로다")
    void 하위_조직_삭제는_상위_조직을_바꾼다() {
        // given — 본부 ⊃ 팀
        String 팀 = 조직을_만든다("메타하위팀", "").get("id").asText();
        JsonNode 본부 = 조직을_만든다("메타본부", "{\"value\":\"%s\",\"type\":\"Group\"}".formatted(팀));
        String 본부경로 = "/scim/v2/Groups/" + 본부.get("id").asText();

        // when
        client.delete().uri("/scim/v2/Groups/" + 팀).exchange().expectStatus().isNoContent();

        // then
        JsonNode 지운뒤 = 읽는다(본부경로);
        assertThat(지운뒤.has("members") && 지운뒤.get("members").size() > 0).isFalse();
        assertThat(lastModified(지운뒤)).isAfter(lastModified(본부));
        assertThat(created(지운뒤)).isEqualTo(created(본부));
    }

    @Test
    @DisplayName("목록·필터 응답도 같은 시각을 싣고, attributes·excludedAttributes 가 meta 의 두 시각을 고르고 뺀다")
    void 목록과_속성_선택() {
        // given
        JsonNode 직원 = 직원을_만든다("meta-list");
        JsonNode 조직 = 조직을_만든다("메타목록팀", "");

        // when
        JsonNode 직원필터 = 읽는다("/scim/v2/Users?filter={f}", "userName eq \"meta-list\"");
        JsonNode 조직필터 = 읽는다("/scim/v2/Groups?filter={f}", "displayName eq \"메타목록팀\"");
        JsonNode 조직필터_멤버제외 = 읽는다("/scim/v2/Groups?filter={f}&excludedAttributes=members", "displayName eq \"메타목록팀\"");
        JsonNode 고름 = 읽는다("/scim/v2/Users/" + 직원.get("id").asText() + "?attributes=meta.lastModified");
        JsonNode 뺌 = 읽는다("/scim/v2/Groups/" + 조직.get("id").asText() + "?excludedAttributes=meta.created");

        // then
        assertThat(lastModified(직원필터.get("Resources").get(0))).isEqualTo(lastModified(직원));
        assertThat(created(조직필터.get("Resources").get(0))).isEqualTo(created(조직));
        assertThat(created(조직필터_멤버제외.get("Resources").get(0))).isEqualTo(created(조직));
        assertThat(lastModified(조직필터_멤버제외.get("Resources").get(0))).isEqualTo(lastModified(조직));
        assertThat(고름.get("meta").has("lastModified")).isTrue();
        assertThat(고름.get("meta").has("created")).isFalse();
        assertThat(뺌.get("meta").has("created")).isFalse();
        assertThat(뺌.get("meta").has("lastModified")).isTrue();
    }
}

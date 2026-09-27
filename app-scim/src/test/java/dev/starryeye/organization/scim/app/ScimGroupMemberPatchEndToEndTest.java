package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조직 멤버 PATCH 를 실제 컨테이너 위에서 IdP 문서의 모양 그대로 보낸다 (조직 멤버 PATCH 설계 §8.4).
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimGroupMemberPatchEndToEndTest {

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

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    private void 직원을_만든다(String userName) {
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s","displayName":"%s","active":true}
                        """.formatted(userName, userName))
                .exchange().expectStatus().isCreated();
    }

    private void 조직을_만든다(String code, String... userNames) {
        String members = String.join(",", java.util.Arrays.stream(userNames)
                .map(name -> "{\"value\":\"%s\",\"type\":\"User\"}".formatted(name)).toList());
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"%s","displayName":"%s","members":[%s]}
                        """.formatted(code, code, members))
                .exchange().expectStatus().isCreated();
    }

    private WebTestClient.ResponseSpec 패치(String code, String operations) {
        return client.patch().uri("/scim/v2/Groups/" + code).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}
                        """.formatted(operations))
                .exchange();
    }

    private List<String> 멤버들(String code) {
        JsonNode group = client.get().uri("/scim/v2/Groups/" + code).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        List<String> values = new ArrayList<>();
        if (group.has("members")) {
            group.get("members").forEach(member -> values.add(member.get("value").asText()));
        }
        return values;
    }

    @Test
    @DisplayName("Okta 식 add(type 없이 display)·필터 remove 는 204 이고 멤버와 권한이 따라온다")
    void Okta_식_추가와_빼기() {
        // given
        직원을_만든다("okta1");
        조직을_만든다("OKTA");

        // when — 추가
        패치("OKTA", """
                [{"op":"add","path":"members","value":[{"value":"okta1","display":"okta1@example.com"}]}]
                """).expectStatus().isNoContent().expectBody().isEmpty();

        // then
        assertThat(멤버들("OKTA")).containsExactly("okta1");
        assertThat(check("user:okta1", "member", "group:OKTA")).isTrue();

        // when — 필터 빼기
        패치("OKTA", """
                [{"op":"remove","path":"members[value eq \\"okta1\\"]"}]
                """).expectStatus().isNoContent();

        // then
        assertThat(멤버들("OKTA")).isEmpty();
        assertThat(check("user:okta1", "member", "group:OKTA")).isFalse();
    }

    @Test
    @DisplayName("Entra 기본 모드의 멤버 빼기는 400 invalidValue 이고 멤버도 권한도 그대로다")
    void Entra_기본_모드_빼기는_거절된다() {
        // given
        직원을_만든다("entra1");
        조직을_만든다("ENTRA", "entra1");

        // when, then — MS 호환성 문서의 기본 모드 예시 모양 그대로
        패치("ENTRA", """
                [{"op":"Remove","path":"members","value":[{"value":"entra1"}]}]
                """).expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("aadOptscim062020"));

        assertThat(멤버들("ENTRA")).containsExactly("entra1");
        assertThat(check("user:entra1", "member", "group:ENTRA")).isTrue();
    }

    @Test
    @DisplayName("Entra 옵션 모드(aadOptscim062020)의 멤버 빼기는 204 다")
    void Entra_옵션_모드_빼기() {
        // given
        직원을_만든다("entra2");
        조직을_만든다("ENTRA2", "entra2");

        // when
        패치("ENTRA2", """
                [{"op":"remove","path":"members[value eq \\"entra2\\"]"}]
                """).expectStatus().isNoContent();

        // then
        assertThat(멤버들("ENTRA2")).isEmpty();
        assertThat(check("user:entra2", "member", "group:ENTRA2")).isFalse();
    }

    @Test
    @DisplayName("replace members 는 목록대로 교체하고 204 다")
    void 전체_교체() {
        // given
        직원을_만든다("r1");
        직원을_만든다("r2");
        직원을_만든다("r3");
        조직을_만든다("REPL", "r1", "r2");

        // when
        패치("REPL", """
                [{"op":"replace","path":"members","value":[{"value":"r2"},{"value":"r3"}]}]
                """).expectStatus().isNoContent();

        // then
        assertThat(멤버들("REPL")).containsExactlyInAnyOrder("r2", "r3");
        assertThat(check("user:r1", "member", "group:REPL")).isFalse();
        assertThat(check("user:r3", "member", "group:REPL")).isTrue();
    }

    @Test
    @DisplayName("같은 조직에 멤버 추가 PATCH 를 동시에 보내도 서로의 멤버를 지우지 않는다")
    void 동시_추가() throws Exception {
        // given
        List<String> 사람들 = List.of("c1", "c2", "c3", "c4", "c5");
        사람들.forEach(this::직원을_만든다);
        조직을_만든다("CONC");

        // when — 동시에 쏜다. 락을 못 잡은 요청(503)은 IdP 처럼 다시 보낸다
        var pool = Executors.newFixedThreadPool(사람들.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            사람들.forEach(id -> futures.add(pool.submit(() -> 넣을때까지("CONC", id))));
            for (Future<Integer> future : futures) {
                assertThat(future.get(1, TimeUnit.MINUTES)).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }

        // then — 락 밖에서 읽은 목록으로 계산하면 먼저 끝난 추가가 나중 요청에 지워진다(설계 §1.3)
        assertThat(멤버들("CONC")).containsExactlyInAnyOrderElementsOf(사람들);
        사람들.forEach(id -> assertThat(check("user:" + id, "member", "group:CONC")).isTrue());
    }

    private int 넣을때까지(String code, String userName) {
        for (int 시도 = 0; 시도 < 10; 시도++) {
            int status = client.patch().uri("/scim/v2/Groups/" + code).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                             "Operations":[{"op":"add","path":"members","value":[{"value":"%s","type":"User"}]}]}
                            """.formatted(userName))
                    .exchange().returnResult(Void.class).getStatus().value();
            if (status != 503) {
                return status;
            }
        }
        return 503;
    }
}

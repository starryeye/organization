package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fixture.Containers;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조직 멤버 PATCH 를 실제 컨테이너 위에서 IdP 문서의 모양 그대로 보낸다 (조직 멤버 PATCH 설계 §8.4).
 *
 * <p>아이디는 서버가 정한다(설계 2026-10-04 §3.1). 직원·조직을 만들 때 응답의 id 를 받아 두고, 멤버 값·경로·Check 는 그 id 로 한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimGroupMemberPatchEndToEndTest {

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
    @Autowired StoreBootstrapper bootstrapper;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

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
    private String 조직을_만든다(String code, String... 직원아이디들) {
        String members = String.join(",", java.util.Arrays.stream(직원아이디들)
                .map(id -> "{\"value\":\"%s\",\"type\":\"User\"}".formatted(id)).toList());
        return 만든_아이디(client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"%s","displayName":"%s","members":[%s]}
                        """.formatted(code, code, members))
                .exchange());
    }

    private WebTestClient.ResponseSpec 패치(String 조직아이디, String operations) {
        return client.patch().uri("/scim/v2/Groups/" + 조직아이디).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}
                        """.formatted(operations))
                .exchange();
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

    @Test
    @DisplayName("Okta 식 add(type 없이 display)·필터 remove 는 204 이고 멤버와 권한이 따라온다")
    void Okta_식_추가와_빼기() {
        // given
        String 직원 = 직원을_만든다("okta1");
        String 조직 = 조직을_만든다("OKTA");

        // when — 추가
        패치(조직, """
                [{"op":"add","path":"members","value":[{"value":"%s","display":"okta1@example.com"}]}]
                """.formatted(직원)).expectStatus().isNoContent().expectBody().isEmpty();

        // then
        assertThat(멤버들(조직)).containsExactly(직원);
        assertThat(check("user:" + 직원, "member", "group:" + 조직)).isTrue();

        // when — 필터 빼기
        패치(조직, """
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]
                """.formatted(직원)).expectStatus().isNoContent();

        // then
        assertThat(멤버들(조직)).isEmpty();
        assertThat(check("user:" + 직원, "member", "group:" + 조직)).isFalse();
    }

    @Test
    @DisplayName("Entra 기본 모드의 멤버 빼기는 400 invalidValue 이고 멤버도 권한도 그대로다")
    void Entra_기본_모드_빼기는_거절된다() {
        // given
        String 직원 = 직원을_만든다("entra1");
        String 조직 = 조직을_만든다("ENTRA", 직원);

        // when, then — MS 호환성 문서의 기본 모드 예시 모양 그대로
        패치(조직, """
                [{"op":"Remove","path":"members","value":[{"value":"%s"}]}]
                """.formatted(직원)).expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("aadOptscim062020"));

        assertThat(멤버들(조직)).containsExactly(직원);
        assertThat(check("user:" + 직원, "member", "group:" + 조직)).isTrue();
    }

    @Test
    @DisplayName("Entra 옵션 모드(aadOptscim062020)의 멤버 빼기는 204 다")
    void Entra_옵션_모드_빼기() {
        // given
        String 직원 = 직원을_만든다("entra2");
        String 조직 = 조직을_만든다("ENTRA2", 직원);
        assertThat(check("user:" + 직원, "member", "group:" + 조직)).isTrue();

        // when
        패치(조직, """
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]
                """.formatted(직원)).expectStatus().isNoContent();

        // then
        assertThat(멤버들(조직)).isEmpty();
        assertThat(check("user:" + 직원, "member", "group:" + 조직)).isFalse();
    }

    @Test
    @DisplayName("replace members 는 목록대로 교체하고 204 다")
    void 전체_교체() {
        // given
        String r1 = 직원을_만든다("r1");
        String r2 = 직원을_만든다("r2");
        String r3 = 직원을_만든다("r3");
        String 조직 = 조직을_만든다("REPL", r1, r2);
        assertThat(check("user:" + r1, "member", "group:" + 조직)).isTrue();

        // when
        패치(조직, """
                [{"op":"replace","path":"members","value":[{"value":"%s"},{"value":"%s"}]}]
                """.formatted(r2, r3)).expectStatus().isNoContent();

        // then
        assertThat(멤버들(조직)).containsExactlyInAnyOrder(r2, r3);
        assertThat(check("user:" + r1, "member", "group:" + 조직)).isFalse();
        assertThat(check("user:" + r3, "member", "group:" + 조직)).isTrue();
    }

    @Test
    @DisplayName("같은 조직에 멤버 추가 PATCH 를 동시에 보내도 서로의 멤버를 지우지 않는다")
    void 동시_추가() throws Exception {
        // given
        List<String> 사람들 = Stream.of("c1", "c2", "c3", "c4", "c5").map(this::직원을_만든다).toList();
        String 조직 = 조직을_만든다("CONC");

        // when — 동시에 쏜다. 락을 못 잡은 요청(503)은 IdP 처럼 다시 보낸다
        var pool = Executors.newFixedThreadPool(사람들.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            사람들.forEach(id -> futures.add(pool.submit(() -> 넣을때까지(조직, id))));
            for (Future<Integer> future : futures) {
                assertThat(future.get(1, TimeUnit.MINUTES)).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }

        // then — 락 밖에서 읽은 목록으로 계산하면 먼저 끝난 추가가 나중 요청에 지워진다(설계 §1.3)
        assertThat(멤버들(조직)).containsExactlyInAnyOrderElementsOf(사람들);
        사람들.forEach(id -> assertThat(check("user:" + id, "member", "group:" + 조직)).isTrue());
    }

    private int 넣을때까지(String 조직아이디, String 직원아이디) {
        int status = 503;
        for (int 시도 = 0; 시도 < 10; 시도++) {
            status = client.patch().uri("/scim/v2/Groups/" + 조직아이디).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                             "Operations":[{"op":"add","path":"members","value":[{"value":"%s","type":"User"}]}]}
                            """.formatted(직원아이디))
                    .exchange().returnResult(Void.class).getStatus().value();
            if (status != 503) {
                return status;
            }
            // 다섯 스레드가 전역 락 하나를 다투므로 즉시 재시도는 다시 충돌하기 쉽다 —
            // ScimRebuildLockScaleTest.보낸다 와 같이 200ms 쉬고 다시 보낸다
            if (시도 < 9) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return status;
    }
}

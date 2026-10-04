package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fixture.Containers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시에 온 SCIM 쓰기가 서로를 지우지 않는다 — 실제 DynamoDB 락과 OpenFGA 위에서 (SCIM 쓰기 락 설계 §7 (b)).
 *
 * <p>시나리오마다 {@link #라운드} 번 반복해 한 번의 타이밍 운에 기대지 않는다. 요청들은 출발선을 맞춰 동시에 보내고, 락을 못
 * 잡은 503 은 IdP 처럼 200ms 쉬었다 다시 보낸다.
 *
 * <p>아이디는 서버가 정한다(설계 2026-10-04 §3.1). 직원·조직을 만들 때 응답의 id 를 받아 경로·멤버 값·Check 에 쓴다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimWriteRaceEndToEndTest {

    private static final int 라운드 = 10;

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

    /** 응답의 상태와 본문. 생성 응답의 본문에서 서버 id 를 받는다. */
    private record 응답(int 상태, String 본문) {
    }

    /** 요청 하나. 503 이면 IdP 처럼 200ms 쉬었다 다시 보낸다 — ScimRebuildLockScaleTest.보낸다 와 같은 관례. */
    private 응답 요청한다(HttpMethod method, String uri, String body) {
        응답 결과 = new 응답(503, "");
        for (int 시도 = 0; 시도 < 10; 시도++) {
            WebTestClient.RequestBodySpec spec = client.method(method).uri(uri);
            var 받은 = (body == null ? spec.exchange()
                    : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange())
                    .expectBody(String.class).returnResult();
            결과 = new 응답(받은.getStatus().value(), 받은.getResponseBody() == null ? "" : 받은.getResponseBody());
            if (결과.상태() != 503) {
                return 결과;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return 결과;
    }

    private int 보낸다(HttpMethod method, String uri, String body) {
        return 요청한다(method, uri, body).상태();
    }

    /** 요청들을 출발선을 맞춰 동시에 보내고 결과를 요청 순서대로 돌려준다. */
    private <T> List<T> 동시에(List<Callable<T>> 요청들) throws Exception {
        var pool = Executors.newFixedThreadPool(요청들.size());
        var 출발 = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> 요청 : 요청들) {
                futures.add(pool.submit(() -> {
                    출발.await();
                    return 요청.call();
                }));
            }
            출발.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(1, TimeUnit.MINUTES));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String 직원본문(String userName) {
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"%s","displayName":"%s","active":true}""".formatted(userName, userName);
    }

    private static String 조직본문(String code, String... 직원아이디들) {
        String members = String.join(",", Arrays.stream(직원아이디들)
                .map(id -> "{\"value\":\"%s\",\"type\":\"User\"}".formatted(id)).toList());
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"%s","displayName":"%s","members":[%s]}""".formatted(code, code, members);
    }

    private static String 패치(String operations) {
        return """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[%s]}""".formatted(operations);
    }

    /** 직원을 만들고 서버가 발급한 id 를 돌려준다. */
    private String 직원을_만든다(String userName) {
        return 생성한다("/scim/v2/Users", 직원본문(userName));
    }

    /** 조직을 만들고 서버가 발급한 id 를 돌려준다. 멤버는 직원의 서버 id 다. */
    private String 조직을_만든다(String code, String... 직원아이디들) {
        return 생성한다("/scim/v2/Groups", 조직본문(code, 직원아이디들));
    }

    private String 생성한다(String uri, String body) {
        응답 결과 = 요청한다(HttpMethod.POST, uri, body);
        assertThat(결과.상태()).as(결과.본문()).isEqualTo(201);
        return 만든_아이디(결과.본문());
    }

    private JsonNode 조회한다(String uri) {
        return client.get().uri(uri).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    @Test
    @DisplayName("같은 직원에 서로 다른 속성을 바꾸는 PATCH 다섯 개를 동시에 보내도 다섯 변경이 모두 남는다")
    void 서로_다른_속성_PATCH() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = 직원을_만든다("race1-" + r);
            String 조직 = 조직을_만든다("R1G" + r, id);
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d — 시작 때는 소속이 있다", r).isTrue();
            String uri = "/scim/v2/Users/" + id;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"새 이름\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"add\",\"path\":\"emails[type eq \\\"work\\\"].value\",\"value\":\"new@example.com\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"name.givenName\",\"value\":\"길동\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\"ext-바뀜\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}"))));

            // then — 락 밖에서 읽은 직원으로 계산하면 늦게 저장된 쪽이 앞의 변경을 지운다(설계 §1.1)
            assertThat(statuses).as("라운드 %d", r).containsOnly(200);
            JsonNode user = 조회한다(uri);
            assertThat(user.get("displayName").asText()).as("라운드 %d", r).isEqualTo("새 이름");
            assertThat(user.get("emails").get(0).get("value").asText()).as("라운드 %d", r).isEqualTo("new@example.com");
            assertThat(user.get("name").get("givenName").asText()).as("라운드 %d", r).isEqualTo("길동");
            assertThat(user.get("externalId").asText()).as("라운드 %d", r).isEqualTo("ext-바뀜");
            assertThat(user.get("active").asBoolean()).as("라운드 %d", r).isFalse();
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d — 비활성이면 권한이 없다", r).isFalse();
        }
    }

    @Test
    @DisplayName("비활성화 PATCH 와 이름 변경 PATCH 가 동시에 와도 비활성이 유지되고 권한이 되살아나지 않는다")
    void 비활성화와_이름_변경() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = 직원을_만든다("race2-" + r);
            String 조직 = 조직을_만든다("R2G" + r, id);
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d — 시작 때는 소속이 있다", r).isTrue();
            String uri = "/scim/v2/Users/" + id;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"개명\"}"))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsOnly(200);
            JsonNode user = 조회한다(uri);
            assertThat(user.get("active").asBoolean()).as("라운드 %d", r).isFalse();
            assertThat(user.get("displayName").asText()).as("라운드 %d", r).isEqualTo("개명");
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d", r).isFalse();
        }
    }

    @Test
    @DisplayName("DELETE 와 PATCH 가 동시에 와도 지운 직원이 되살아나지 않는다")
    void 삭제와_변경() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = 직원을_만든다("race3-" + r);
            String 조직 = 조직을_만든다("R3G" + r, id);
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d — 시작 때는 소속이 있다", r).isTrue();
            String uri = "/scim/v2/Users/" + id;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.DELETE, uri, null),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"x\"}"))));

            // then — PATCH 가 먼저면 200 뒤 삭제, DELETE 가 먼저면 PATCH 는 404. 어느 쪽이든 직원은 없다(설계 §1.2)
            assertThat(statuses.get(0)).as("라운드 %d", r).isEqualTo(204);
            assertThat(statuses.get(1)).as("라운드 %d", r).isIn(200, 404);
            client.get().uri(uri).exchange().expectStatus().isNotFound();
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d", r).isFalse();
        }
    }

    /**
     * 이 시나리오는 <b>락이 두 POST 를 한 줄로 세운다</b>는 것만 증명한다.
     *
     * <p>DynamoDB Local 의 GSI 는 즉시 반영되므로 두 번째 POST 가 첫 번째를 GSI 로 찾는다. 실제 DynamoDB 의
     * GSI 는 결과적 일관성이라, 첫 POST 직후(반영 전)에 온 두 번째 POST 는 통과할 수 있다 — 설계 §10
     * "userName 의 GSI 지연 틈" 이 수용한 한계다({@code docs/superpowers/specs/2026-09-28-scim-write-lock-design.md}).
     */
    @Test
    @DisplayName("대소문자만 다른 userName 으로 동시에 만들면 하나만 201 이고 하나는 409 다")
    void 대소문자만_다른_생성() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String 대문자 = "Case-" + r;
            String 소문자 = "case-" + r;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Users", 직원본문(대문자)),
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Users", 직원본문(소문자))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsExactlyInAnyOrder(201, 409);
            JsonNode found = client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"" + 소문자 + "\"")
                    .exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(found.get("totalResults").asInt()).as("라운드 %d", r).isEqualTo(1);
        }
    }

    /**
     * 서버가 id 를 정하므로 두 POST 는 서로 다른 id 를 받는다 — 겹침을 판정하는 것은 <b>같은 {@code externalId}</b> 다(설계 2026-10-04 §3.2).
     * 생성은 쓰기 락(전역 하나)을 잡은 안에서 {@code externalId} 로 GSI3 를 읽고 본 테이블로 다시 확인하므로, 락이 두 POST 를 한 줄로 세우고
     * 뒤의 것이 앞의 것을 본다. 위 userName 시나리오와 같이 DynamoDB Local 의 GSI 가 즉시 반영된다는 점에 기댄다 — 실제 DynamoDB 의 GSI 는
     * 결과적 일관성이라 첫 POST 직후의 두 번째는 통과할 수 있다(설계 §3.2 가 받아들인 한계). 본 테이블 재확인은 낡은 후보를 걸러낼 뿐 없는 후보를
     * 찾아 주지 못한다.
     */
    @Test
    @DisplayName("같은 조직을 동시에 만들면 하나만 201 이고 하나는 409 다")
    void 같은_조직_동시_생성() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String code = "DUP" + r;

            // when
            List<응답> 응답들 = 동시에(List.of(
                    () -> 요청한다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code)),
                    () -> 요청한다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code))));

            // then — 하나만 만들어졌고(409 는 scimType uniqueness), 그 하나가 externalId 로 찾아진다
            assertThat(응답들.stream().map(응답::상태).toList()).as("라운드 %d", r).containsExactlyInAnyOrder(201, 409);
            String 거절된본문 = 응답들.stream().filter(결과 -> 결과.상태() == 409).findFirst().orElseThrow().본문();
            assertThat(new ObjectMapper().readTree(거절된본문).path("scimType").asText()).as("라운드 %d", r).isEqualTo("uniqueness");
            String 만들어진 = 만든_아이디(응답들.stream().filter(결과 -> 결과.상태() == 201).findFirst().orElseThrow().본문());
            JsonNode found = client.get().uri("/scim/v2/Groups?filter={f}", "externalId eq \"" + code + "\"")
                    .exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(found.get("totalResults").asInt()).as("라운드 %d", r).isEqualTo(1);
            assertThat(found.get("Resources").get(0).get("id").asText()).as("라운드 %d", r).isEqualTo(만들어진);
        }
    }

    @Test
    @DisplayName("직원 비활성화와 그 직원을 조직에 넣는 PATCH 가 동시에 와도 멤버십은 생기고 권한은 없다")
    void 비활성화와_조직_추가() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = 직원을_만든다("race6-" + r);
            String 조직 = 조직을_만든다("R6G" + r);

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.PATCH, "/scim/v2/Users/" + id,
                            패치("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}")),
                    () -> 보낸다(HttpMethod.PATCH, "/scim/v2/Groups/" + 조직,
                            패치("{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"%s\",\"type\":\"User\"}]}".formatted(id)))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsExactlyInAnyOrder(200, 204);
            JsonNode group = 조회한다("/scim/v2/Groups/" + 조직);
            List<String> members = new ArrayList<>();
            group.get("members").forEach(member -> members.add(member.get("value").asText()));
            assertThat(members).as("라운드 %d", r).containsExactly(id);
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d", r).isFalse();
        }
    }
}

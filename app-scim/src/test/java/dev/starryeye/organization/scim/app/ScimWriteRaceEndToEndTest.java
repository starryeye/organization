package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시에 온 SCIM 쓰기가 서로를 지우지 않는다 — 실제 DynamoDB 락과 OpenFGA 위에서 (SCIM 쓰기 락 설계 §7 (b)).
 *
 * <p>시나리오마다 {@link #라운드} 번 반복해 한 번의 타이밍 운에 기대지 않는다. 요청들은 출발선을 맞춰 동시에 보내고, 락을 못
 * 잡은 503 은 IdP 처럼 200ms 쉬었다 다시 보낸다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimWriteRaceEndToEndTest {

    private static final int 라운드 = 10;

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

    /** 요청 하나. 503 이면 IdP 처럼 200ms 쉬었다 다시 보낸다 — ScimRebuildLockScaleTest.보낸다 와 같은 관례. */
    private int 보낸다(HttpMethod method, String uri, String body) {
        int status = 503;
        for (int 시도 = 0; 시도 < 10; 시도++) {
            WebTestClient.RequestBodySpec spec = client.method(method).uri(uri);
            status = (body == null ? spec.exchange()
                    : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange())
                    .returnResult(Void.class).getStatus().value();
            if (status != 503) {
                return status;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status;
    }

    /** 요청들을 출발선을 맞춰 동시에 보내고 상태코드를 요청 순서대로 돌려준다. */
    private List<Integer> 동시에(List<Callable<Integer>> 요청들) throws Exception {
        var pool = Executors.newFixedThreadPool(요청들.size());
        var 출발 = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> 요청 : 요청들) {
                futures.add(pool.submit(() -> {
                    출발.await();
                    return 요청.call();
                }));
            }
            출발.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(1, TimeUnit.MINUTES));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String 직원본문(String userName) {
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"%s","displayName":"%s","active":true}""".formatted(userName, userName);
    }

    private static String 조직본문(String code, String... userNames) {
        String members = String.join(",", Arrays.stream(userNames)
                .map(name -> "{\"value\":\"%s\",\"type\":\"User\"}".formatted(name)).toList());
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"%s","displayName":"%s","members":[%s]}""".formatted(code, code, members);
    }

    private static String 패치(String operations) {
        return """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[%s]}""".formatted(operations);
    }

    private void 직원을_만든다(String userName) {
        assertThat(보낸다(HttpMethod.POST, "/scim/v2/Users", 직원본문(userName))).isEqualTo(201);
    }

    private void 조직을_만든다(String code, String... userNames) {
        assertThat(보낸다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code, userNames))).isEqualTo(201);
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
            String id = "race1-" + r;
            String 조직 = "R1G" + r;
            직원을_만든다(id);
            조직을_만든다(조직, id);
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
            String id = "race2-" + r;
            String 조직 = "R2G" + r;
            직원을_만든다(id);
            조직을_만든다(조직, id);
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
            String id = "race3-" + r;
            String 조직 = "R3G" + r;
            직원을_만든다(id);
            조직을_만든다(조직, id);
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

    @Test
    @DisplayName("같은 조직을 동시에 만들면 하나만 201 이고 하나는 409 다")
    void 같은_조직_동시_생성() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String code = "DUP" + r;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code)),
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsExactlyInAnyOrder(201, 409);
            client.get().uri("/scim/v2/Groups/" + code).exchange().expectStatus().isOk();
        }
    }

    @Test
    @DisplayName("직원 비활성화와 그 직원을 조직에 넣는 PATCH 가 동시에 와도 멤버십은 생기고 권한은 없다")
    void 비활성화와_조직_추가() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = "race6-" + r;
            String 조직 = "R6G" + r;
            직원을_만든다(id);
            조직을_만든다(조직);

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

package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 요청 → 도메인 → 튜플 → OpenFGA/DynamoDB 전 구간을 실제 컨테이너 위에서 확인한다.
 *
 * <p>테스트는 순서에 의존한다. 앞선 테스트가 만든 상태 위에서 다음 테스트가 변경을 가한다 —
 * SCIM 이 push 모델이라는 사실 자체가 그런 순차성을 전제하기 때문이다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimEndToEndTest {

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
    @Autowired DirectoryStateRepository state;
    @Autowired TupleSnapshotRepository snapshots;
    @Autowired SnapshotArchiveUseCase archive;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("직원과 조직을 만들고 멤버로 넣으면 OpenFGA 에 소속이 반영된다")
    void 직원과_조직을_만들고_연결한다() {
        // given, when — 직원 둘
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"kim","displayName":"김철수","active":true}
                        """)
                .exchange().expectStatus().isCreated();
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"park","displayName":"박민수","active":true}
                        """)
                .exchange().expectStatus().isCreated();

        // when — 조직 둘, 하위 조직과 직원을 멤버로
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV002","displayName":"백엔드팀",
                         "members":[{"value":"kim","type":"User"}]}
                        """)
                .exchange().expectStatus().isCreated();
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"개발본부",
                         "members":[{"value":"DEV002","type":"Group"},
                                    {"value":"park","type":"User"}]}
                        """)
                .exchange().expectStatus().isCreated();

        // then — 직속 소속
        assertThat(check("user:kim", "member", "group:DEV002")).isTrue();
        // then — 하위 조직을 통한 롤업. 이것이 인가 모델의 존재 이유다
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        // then — 상속은 상위로만 향한다. 상위 직속인 park 은 하위 조직의 멤버가 아니다
        assertThat(check("user:park", "member", "group:DEV002")).isFalse();

        var loaded = state.loadAll().block();
        assertThat(loaded.users()).containsOnlyKeys("kim", "park");
        assertThat(loaded.groups().get("DEV001").displayName()).isEqualTo("개발본부");
    }

    @Test
    @Order(2)
    @DisplayName("PATCH 로 멤버를 빼면 그 소속만 사라지고 나머지는 남는다")
    void PATCH로_멤버를_뺀다() {
        // given, when
        client.patch().uri("/scim/v2/Groups/DEV002").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"remove","path":"members[value eq \\"kim\\"]"}]}
                        """)
                .exchange().expectStatus().isOk();

        // then
        assertThat(check("user:kim", "member", "group:DEV002")).isFalse();
        assertThat(check("user:kim", "member", "group:DEV001")).isFalse();
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("직원을 비활성화하면 남은 소속의 튜플도 사라진다")
    void 비활성화가_소속을_지운다() {
        // given, when
        client.patch().uri("/scim/v2/Users/park").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":false}]}
                        """)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false);

        // then — 비활성 직원에게 권한이 남지 않는다
        assertThat(check("user:park", "member", "group:DEV001")).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("직원을 다시 활성화하면 소속이 되살아난다")
    void 재활성화가_소속을_되살린다() {
        // given, when
        client.patch().uri("/scim/v2/Users/park").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":true}]}
                        """)
                .exchange().expectStatus().isOk();

        // then
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("조직을 삭제하면 상위 조직에서의 연결도 함께 끊긴다")
    void 조직을_삭제한다() {
        // given — DEV002 는 DEV001 의 하위 조직이다
        // when
        client.delete().uri("/scim/v2/Groups/DEV002")
                .exchange().expectStatus().isNoContent();

        // then
        assertThat(state.loadAll().block().groups()).doesNotContainKey("DEV002");
        assertThat(state.loadAll().block().groups().get("DEV001").members())
                .noneMatch(member -> member.id().equals("DEV002"));
    }

    @Test
    @Order(6)
    @DisplayName("없는 리소스를 조회하면 SCIM Error 스키마로 404 가 돌아온다")
    void 없는_리소스는_SCIM_에러다() {
        // given, when, then
        client.get().uri("/scim/v2/Groups/DEV999")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("404");
    }

    @Test
    @Order(7)
    @DisplayName("아카이빙은 현재상태를 SCIM 소스 스냅샷으로 남긴다")
    void 아카이빙이_스냅샷을_남긴다() {
        // given — 앞선 테스트들이 만든 상태가 남아 있다
        // when
        var run = archive.execute().block();

        // then
        assertThat(run.status().name()).isEqualTo("SUCCEEDED");
        var snapshot = snapshots.findLatest().block();
        assertThat(snapshot.source().name()).isEqualTo("SCIM");
        assertThat(snapshot.id()).endsWith("-SCIM");
        assertThat(snapshot.tuples())
                .anyMatch(tuple -> tuple.object().equals("group:DEV001"));
    }

    @Test
    @Order(8)
    @DisplayName("헬스체크가 DynamoDB 와 OpenFGA 연결을 모두 UP 으로 보고한다")
    void 헬스체크가_UP이다() {
        // given, when, then
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.dynamoDb.status").isEqualTo("UP")
                .jsonPath("$.components.openFga.status").isEqualTo("UP");
    }
}

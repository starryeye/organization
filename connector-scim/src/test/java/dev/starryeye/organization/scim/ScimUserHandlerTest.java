package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimUserHandlerTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        var useCase = new IncrementalSyncUseCase(state, writer, checker, lock, 0, IncrementalSyncUseCase.DriftObserver.NOOP);
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)))).build();
    }

    @Test
    @DisplayName("직원을 생성하면 201 과 함께 SCIM User 본문이 돌아온다")
    void 직원을_생성한다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"emp-1001","userName":"kim","displayName":"김철수",
                 "emails":[{"value":"kim@example.com","primary":true}],"active":true}
                """;

        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo("kim")
                .jsonPath("$.userName").isEqualTo("kim")
                .jsonPath("$.active").isEqualTo(true);

        assertThat(state.users).containsKey("kim");
    }

    @Test
    @DisplayName("userName 이 바뀐 뒤 같은 사람을 새 userName 으로 다시 생성하면 409 로 막는다")
    void userName_중복_생성은_409다() {
        // given — kim 으로 만들어진 뒤 userName 만 kim.lee 로 바뀐 직원. id 는 SCIM 의 정체성이라
        // userName 변경을 따라가지 않는다(의도된 동작)
        state.saveUser(new DirectoryUser("kim", "emp-1001", "kim.lee", "김철수", null, true)).block();
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"kim.lee","displayName":"김철수"}
                """;

        // when, then — id 로는 못 찾으니 막지 않으면 같은 사람의 레코드가 둘 생긴다
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("uniqueness");

        assertThat(state.users).containsOnlyKeys("kim");
    }

    @Test
    @DisplayName("요청 본문이 비어 있으면 400 invalidSyntax 로 거절한다")
    void 본문이_비어있으면_400이다() {
        // given, when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidSyntax")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);

        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type 은 500 이 아니라 그 상태코드 그대로 SCIM Error 로 거절한다")
    void 지원하지_않는_ContentType은_500이_아니다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"kim"}
                """;

        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.TEXT_PLAIN).bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("있는 직원을 조회하면 200 과 함께 SCIM User 본문이 돌아온다")
    void 있는_직원을_조회한다() {
        // given
        state.saveUser(new DirectoryUser("kim", "emp-1001", "kim", "김철수",
                "kim@example.com", true)).block();

        // when, then
        client.get().uri("/scim/v2/Users/kim")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("kim")
                .jsonPath("$.userName").isEqualTo("kim")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.USER);
    }

    @Test
    @DisplayName("userName 이 없으면 400 invalidSyntax 로 거절한다")
    void userName이_없으면_400이다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"displayName":"김철수"}
                """;

        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidSyntax");
    }

    @Test
    @DisplayName("PATCH 로 비활성화하면 소속 조직의 튜플이 사라진다")
    void 비활성화가_튜플을_지운다() {
        // given — kim 의 튜플이 이미 OpenFGA 에 있어야 이번 비활성화가 실제 삭제 델타를 만든다
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.allowed.add(RelationTuple.directMember("kim", "DEV002"));
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","path":"active","value":false}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Users/kim")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false);

        assertThat(writer.appliedDeltas.get(0).toDelete()).isNotEmpty();
    }

    @Test
    @DisplayName("직원을 삭제하면 204 를 돌려주고 소속 조직에서도 빠진다")
    void 직원을_삭제한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when, then
        client.delete().uri("/scim/v2/Users/kim")
                .exchange()
                .expectStatus().isNoContent();

        assertThat(state.users).doesNotContainKey("kim");
        assertThat(state.groups.get("DEV002").members()).isEmpty();
    }

    @Test
    @DisplayName("PUT 은 리소스를 통째로 교체한다")
    void PUT은_전체를_교체한다() {
        // given
        state.saveUser(new DirectoryUser("kim", "emp-1001", "kim", "김철수",
                "old@example.com", true)).block();
        String body = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"kim\",\"displayName\":\"김철수\","
                + "\"emails\":[{\"value\":\"new@example.com\",\"primary\":true}],\"active\":true}";

        // when, then
        client.put().uri("/scim/v2/Users/kim")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        assertThat(state.users.get("kim").email()).isEqualTo("new@example.com");
    }

    @Test
    @DisplayName("변경 락을 못 잡으면 503 이고 SCIM 에러 형식을 지킨다")
    void 락을_못_잡으면_503이다() {
        // given — 다른 인스턴스가 락을 쥐고 있는 상황(재적재 등)을 재현한다
        lock.failAcquire = true;

        // when, then — IdP 는 503 을 재시도 신호로 보므로 프로비저닝이 유실되지 않는다
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"gd.hong","displayName":"홍길동"}""")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("503");
    }

    @Test
    @DisplayName("락을 못 잡은 동안에도 조회는 통과한다")
    void 락을_못_잡은_동안에도_조회는_통과한다() {
        // given — 조회는 락을 타지 않으므로 무슨 일이 벌어지는지 들여다보는 것이 그 순간 가장 필요한 일이다
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        lock.failAcquire = true;

        // when, then
        client.get().uri("/scim/v2/Users/kim")
                .exchange().expectStatus().isOk();
    }
}

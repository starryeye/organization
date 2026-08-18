package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
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
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        var useCase = new IncrementalSyncUseCase(state, writer);
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase))).build();
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
                .expectBody()
                .jsonPath("$.id").isEqualTo("kim")
                .jsonPath("$.userName").isEqualTo("kim")
                .jsonPath("$.active").isEqualTo(true);

        assertThat(state.users).containsKey("kim");
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
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidSyntax");
    }

    @Test
    @DisplayName("PATCH 로 비활성화하면 소속 조직의 튜플이 사라진다")
    void 비활성화가_튜플을_지운다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
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
}

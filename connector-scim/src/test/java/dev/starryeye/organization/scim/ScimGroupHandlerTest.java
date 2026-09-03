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
import dev.starryeye.organization.core.usecase.LockObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimGroupHandlerTest {

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
        var useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)))).build();
    }

    @Test
    @DisplayName("조직을 생성하면 201 과 함께 SCIM Group 본문이 돌아온다")
    void 조직을_생성한다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV001","displayName":"개발본부","members":[]}
                """;

        // when, then
        client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo("DEV001")
                .jsonPath("$.displayName").isEqualTo("개발본부")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.GROUP);

        assertThat(state.groups).containsKey("DEV001");
    }

    @Test
    @DisplayName("이미 있는 조직코드로 생성하면 409 uniqueness 로 거절한다")
    void 중복_생성은_409다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of())).block();
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV001","displayName":"개발본부"}
                """;

        // when, then
        client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("uniqueness")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("요청 본문이 비어 있으면 400 invalidSyntax 로 거절한다")
    void 본문이_비어있으면_400이다() {
        // given, when, then
        client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidSyntax")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);

        assertThat(state.groups).isEmpty();
    }

    @Test
    @DisplayName("있는 조직을 조회하면 200 과 함께 SCIM Group 본문이 돌아온다")
    void 있는_조직을_조회한다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of())).block();

        // when, then
        client.get().uri("/scim/v2/Groups/DEV001")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("DEV001")
                .jsonPath("$.displayName").isEqualTo("개발본부")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.GROUP);
    }

    @Test
    @DisplayName("PUT 은 조직을 통째로 교체한다")
    void PUT은_조직을_교체한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String body = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"externalId\":\"DEV002\",\"displayName\":\"신설백엔드팀\","
                + "\"members\":[{\"value\":\"kim\",\"type\":\"User\"}]}";

        // when, then
        client.put().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("신설백엔드팀");

        assertThat(state.groups.get("DEV002").displayName()).isEqualTo("신설백엔드팀");
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("없는 조직을 조회하면 404 와 SCIM Error 본문이 돌아온다")
    void 없는_조직_조회는_404다() {
        // given, when, then
        client.get().uri("/scim/v2/Groups/DEV999")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("404")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("DEV999"));
    }

    @Test
    @DisplayName("PATCH 로 멤버를 추가하면 튜플이 생성되고 200 이 돌아온다")
    void PATCH로_멤버를_추가한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members",
                                "value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.members[0].value").isEqualTo("kim");

        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("지원하지 않는 PATCH path 는 400 invalidPath 로 거절한다")
    void 지원하지_않는_path는_400이다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","path":"emails[type eq \\"work\\"].value",
                                "value":"x@example.com"}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidPath");
    }

    @Test
    @DisplayName("조직을 삭제하면 204 를 돌려주고 튜플과 상태가 사라진다")
    void 조직을_삭제한다() {
        // given — kim 의 튜플이 이미 OpenFGA 에 있어야 이번 삭제가 실제 삭제 델타를 만든다
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.allowed.add(RelationTuple.directMember("kim", "DEV002"));

        // when, then
        client.delete().uri("/scim/v2/Groups/DEV002")
                .exchange()
                .expectStatus().isNoContent();

        assertThat(state.groups).doesNotContainKey("DEV002");
        assertThat(writer.appliedDeltas.get(0).toDelete()).isNotEmpty();
    }

    @Test
    @DisplayName("일부 튜플 적용에 실패하면 500 을 돌려 IdP 가 재시도하게 한다")
    void 부분_실패는_500이다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        writer.failFor(tuple -> true);
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members",
                                "value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("ServiceProviderConfig 는 지원하지 않는 기능을 정직하게 광고한다")
    void 지원기능을_광고한다() {
        // given, when, then
        client.get().uri("/scim/v2/ServiceProviderConfig")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.patch.supported").isEqualTo(true)
                .jsonPath("$.filter.supported").isEqualTo(false)
                .jsonPath("$.bulk.supported").isEqualTo(false);
    }
}

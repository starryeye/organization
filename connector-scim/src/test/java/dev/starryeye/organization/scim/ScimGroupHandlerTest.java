package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(state, query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();
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
                .jsonPath("$.id").value(id -> assertThat(UUID.fromString((String) id)).hasToString((String) id))
                .jsonPath("$.externalId").isEqualTo("DEV001")
                .jsonPath("$.displayName").isEqualTo("개발본부")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.GROUP);

        assertThat(state.groups.values()).extracting(DirectoryGroup::externalId).containsExactly("DEV001");
    }

    @Test
    @DisplayName("조직 POST 는 서버가 발급한 UUID 를 id 로 돌려주고 externalId 는 속성으로 둔다 — 본문의 id 는 무시한다")
    void POST는_서버가_id_를_발급한다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"id":"client-chosen",
                 "externalId":"DEV001","displayName":"개발본부","members":[]}
                """;

        // when
        String id = client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString();

        // then
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(state.groups).containsOnlyKeys(id);
        assertThat(state.groups.get(id).externalId()).isEqualTo("DEV001");
        client.get().uri("/scim/v2/Groups/" + id).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.externalId").isEqualTo("DEV001")
                .jsonPath("$.displayName").isEqualTo("개발본부");
    }

    @Test
    @DisplayName("이미 같은 externalId 를 쓰는 조직이 있으면 생성은 409 uniqueness 로 거절한다")
    void 중복_생성은_409다() {
        // given — 아이디는 externalId 와 무관하다
        state.saveGroup(new DirectoryGroup("0b1c4f7a-0000-4000-8000-000000000001", "DEV001", "개발본부", Set.of())).block();
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

        assertThat(state.groups).containsOnlyKeys("0b1c4f7a-0000-4000-8000-000000000001");
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
    @DisplayName("PATCH 로 멤버를 추가하면 튜플이 생성되고 본문 없이 204 가 돌아온다")
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
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("attributes 가 있으면 PATCH 는 200 과 요청한 속성을 돌려준다 — RFC 7644 §3.5.2 의 MUST")
    void PATCH_attributes가_있으면_200이다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members",
                                "value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002?attributes=displayName")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("백엔드팀")
                .jsonPath("$.members").doesNotExist();

        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("excludedAttributes 만 있으면 PATCH 는 지금처럼 본문 없이 204 다")
    void PATCH_excludedAttributes만_있으면_204다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members",
                                "value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002?excludedAttributes=members")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("잘못된 attributes 는 쓰기 전에 400 이고 writer 는 아무것도 받지 않으며 멤버도 그대로다")
    void PATCH_잘못된_attributes는_400이다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members",
                                "value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002?attributes=nosuchattr")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");

        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.groups.get("DEV002").members()).isEmpty();
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
    @DisplayName("조직 계층이 순환 검사 한도를 넘으면 400 invalidValue 다 — 다시 보내도 늘 넘는다")
    void 계층이_너무_크면_400이다() {
        // given — G1 ⊃ … ⊃ G10101
        int 깊이 = 10_100;
        for (int i = 1; i <= 깊이; i++) {
            state.groups.put("G" + i, new DirectoryGroup("G" + i, "G" + i, "G" + i, Set.of(MemberRef.group("G" + (i + 1)))));
        }
        state.groups.put("G" + (깊이 + 1), new DirectoryGroup("G" + (깊이 + 1), "G" + (깊이 + 1), "맨 아래", Set.of()));
        state.groups.put("NEW", new DirectoryGroup("NEW", "NEW", "새 조직", Set.of()));
        String body = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"NEW","type":"Group"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/G" + (깊이 + 1))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");
    }

    @Test
    @DisplayName("없는 조직에 PATCH 하면 404 이고 아무것도 쓰지 않는다")
    void 없는_조직_PATCH는_404다() {
        // given
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/NONE")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isNotFound();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("Entra 기본 모드의 멤버 빼기는 400 invalidValue 이고 멤버도 튜플도 그대로다")
    void Entra_기본_모드_빼기는_400이다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"Remove","path":"members","value":[{"value":"kim"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("aadOptscim062020"));

        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("PUT 은 전체 교체로 처리하고 200 과 리소스를 돌려준다")
    void PUT은_전체_교체다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveUser(new DirectoryUser("lee", null, "lee", "이영희", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV002","displayName":"플랫폼팀",
                 "members":[{"value":"lee","type":"User"}]}
                """;

        // when, then
        client.put().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("플랫폼팀")
                .jsonPath("$.members[0].value").isEqualTo("lee");

        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("없는 조직에 PUT 하면 404 다")
    void 없는_조직_PUT은_404다() {
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"NONE","displayName":"없음","members":[]}
                """;
        client.put().uri("/scim/v2/Groups/NONE")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("없는 조직을 DELETE 하면 404 다")
    void 없는_조직_DELETE는_404다() {
        client.delete().uri("/scim/v2/Groups/NONE").exchange().expectStatus().isNotFound();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("ServiceProviderConfig 는 지원하는 기능과 지원하지 않는 기능을 정직하게 선언한다")
    void 지원기능을_선언한다() {
        // given, when, then
        client.get().uri("/scim/v2/ServiceProviderConfig")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.patch.supported").isEqualTo(true)
                .jsonPath("$.filter.supported").isEqualTo(true)
                .jsonPath("$.bulk.supported").isEqualTo(false);
    }
}

package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SCIM POST 의 id 가 ScimIds 후보에서 나오고 유스케이스가 정한 id 로 답하는지 본다(설계 2026-11-05 I3). */
class ScimIdIssueTest {

    private static final String 직원_id = "46af28dd-ee3f-5eed-9506-e28af7d370de";
    private static final String 조직_id = "2481ef44-a4ea-5cfe-95f6-9d2420431168";

    private WebTestClient 클라이언트(ScimIds ids) {
        var state = new FakeStateRepository();
        var useCase = new IncrementalSyncUseCase(state, new FakeTupleWriter(), new FakeTupleChecker(), new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        return WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(query, useCase, IgnoredAttributeObserver.NOOP, ids),
                        new ScimGroupHandler(state, query, useCase, new StateMemberTypeResolver(state), ids),
                        new ScimListHandler(new ScimUserListing(query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();
    }

    private static String 직원_본문(String externalId, String userName) {
        String 외부 = externalId == null ? "" : "\"externalId\":\"" + externalId + "\",";
        return "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]," + 외부
                + "\"userName\":\"" + userName + "\",\"displayName\":\"" + userName + "\",\"active\":true}";
    }

    private static EntityExchangeResult<Map> 직원을_만든다(WebTestClient client, String externalId, String userName) {
        return client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(직원_본문(externalId, userName))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult();
    }

    private static void v4_이다(Object id) {
        assertThat(UUID.fromString((String) id).version()).isEqualTo(4);
    }

    @Test
    @DisplayName("꺼짐이면 externalId 가 있어도 무작위 id 다")
    void 꺼짐이면_externalId_가_있어도_무작위_id_다() {
        // given
        var client = 클라이언트(ScimIds.random());

        // when
        Object id = 직원을_만든다(client, "00u1abcd", "kim").getResponseBody().get("id");

        // then
        v4_이다(id);
        assertThat(id).isNotEqualTo(직원_id);
    }

    @Test
    @DisplayName("켜면 직원 id 가 externalId 에서 나온다")
    void 켜면_직원_id_가_externalId_에서_나온다() {
        // given
        var client = 클라이언트(ScimIds.fromExternalId());

        // when
        var result = 직원을_만든다(client, "00u1abcd", "kim");

        // then
        assertThat(result.getResponseBody().get("id")).isEqualTo(직원_id);
        assertThat(result.getResponseHeaders().getLocation().toString()).endsWith("/scim/v2/Users/" + 직원_id);
    }

    @Test
    @DisplayName("켜면 조직 id 가 externalId 에서 나온다")
    void 켜면_조직_id_가_externalId_에서_나온다() {
        // given
        var client = 클라이언트(ScimIds.fromExternalId());
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"00u1abcd","displayName":"재무팀"}
                """;

        // when
        var result = client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult();

        // then
        assertThat(result.getResponseBody().get("id")).isEqualTo(조직_id);
        assertThat(result.getResponseHeaders().getLocation().toString()).endsWith("/scim/v2/Groups/" + 조직_id);
    }

    @Test
    @DisplayName("켜도 externalId 가 없거나 공백뿐이면 무작위다")
    void 켜도_externalId_가_없거나_공백뿐이면_무작위다() {
        // given
        var client = 클라이언트(ScimIds.fromExternalId());

        // when
        Object 없음 = 직원을_만든다(client, null, "kim").getResponseBody().get("id");
        Object 공백 = 직원을_만든다(client, "  ", "park").getResponseBody().get("id");

        // then
        v4_이다(없음);
        v4_이다(공백);
    }

    @Test
    @DisplayName("켜고 같은 externalId 의 두 번째 직원은 409 uniqueness 다")
    void 켜고_같은_externalId_의_두_번째_직원은_409_uniqueness_다() {
        // given
        var client = 클라이언트(ScimIds.fromExternalId());
        직원을_만든다(client, "00u1abcd", "kim");

        // when, then
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(직원_본문("00u1abcd", "park"))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");
        client.get().uri("/scim/v2/Users/" + 직원_id).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.userName").isEqualTo("kim");
    }

    @Test
    @DisplayName("후보에서 물러서면 정한 id 로 답한다")
    void 후보에서_물러서면_정한_id_로_답한다() {
        // given — A 가 후보 id 를 쓰고 있지만 externalId 는 다른 값으로 바뀌었다
        var client = 클라이언트(ScimIds.fromExternalId());
        직원을_만든다(client, "00u1abcd", "kim");
        client.put().uri("/scim/v2/Users/" + 직원_id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(직원_본문("00u9zzzz", "kim"))
                .exchange().expectStatus().isOk();

        // when
        var result = 직원을_만든다(client, "00u1abcd", "park");

        // then
        Object id = result.getResponseBody().get("id");
        v4_이다(id);
        assertThat(id).isNotEqualTo(직원_id);
        assertThat(result.getResponseHeaders().getLocation().toString()).endsWith("/scim/v2/Users/" + id);
        client.get().uri("/scim/v2/Users/" + 직원_id).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.userName").isEqualTo("kim");
    }

    private static String 조직_본문(String externalId, String displayName) {
        return "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"externalId\":\"" + externalId
                + "\",\"displayName\":\"" + displayName + "\"}";
    }

    @Test
    @DisplayName("조직 후보에서 물러서면 정한 id 로 답한다")
    void 조직_후보에서_물러서면_정한_id_로_답한다() {
        // given — 조직 A 가 후보 id 를 쓰고 있지만 externalId 는 다른 값으로 바뀌었다
        var client = 클라이언트(ScimIds.fromExternalId());
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(조직_본문("00u1abcd", "조직 A"))
                .exchange().expectStatus().isCreated();
        client.put().uri("/scim/v2/Groups/" + 조직_id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(조직_본문("00u9zzzz", "조직 A"))
                .exchange().expectStatus().isOk();

        // when
        var result = client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(조직_본문("00u1abcd", "조직 B"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult();

        // then
        Object id = result.getResponseBody().get("id");
        v4_이다(id);
        assertThat(id).isNotEqualTo(조직_id);
        assertThat(result.getResponseHeaders().getLocation().toString()).endsWith("/scim/v2/Groups/" + id);
        client.get().uri("/scim/v2/Groups/" + 조직_id).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("조직 A");
    }
}

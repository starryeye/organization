package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어느 라우트에도 맞지 않는 요청도 SCIM Error 로 답하는지 본다(점검 S7) — 스프링의 기본 404 본문이 나가면 IdP 가 읽지 못한다.
 */
class ScimRouteMissTest {

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var state = new FakeStateRepository();
        var useCase = new IncrementalSyncUseCase(state, new FakeTupleWriter(), new FakeTupleChecker(), new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase, new StateMemberTypeResolver(state)),
                        new ScimListHandler(new ScimUserListing(state, query, bookmarks),
                                new ScimGroupListing(state, query, bookmarks)))).build();
    }

    @Test
    @DisplayName("있는 경로에 틀린 메서드는 405 이고 Allow 에 그 경로가 받는 메서드를 싣는다 — SCIM Error 형식이다(점검 S7)")
    void 틀린_메서드는_405다() {
        // when, then
        client.delete().uri("/scim/v2/Users").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectHeader().value(HttpHeaders.ALLOW, allow -> assertThat(allow).contains("GET").contains("POST"))
                .expectBody().jsonPath("$.status").isEqualTo("405")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("Allow 에는 그 경로가 받는 메서드만 싣는다 — {id} 경로는 조회·교체·부분 수정·삭제를 받고 POST 는 받지 않는다")
    void Allow_는_받는_메서드만_싣는다() {
        // when, then
        client.post().uri("/scim/v2/Groups/DEV002").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().value(HttpHeaders.ALLOW, allow -> assertThat(allow)
                        .contains("GET").contains("PUT").contains("PATCH").contains("DELETE").doesNotContain("POST"));
    }

    @Test
    @DisplayName("서버 루트에 틀린 메서드도 405 이고 Allow 에 GET 을 싣는다 — 404 로 새지 않는다")
    void 서버_루트의_틀린_메서드는_405다() {
        // when, then
        client.delete().uri("/scim/v2").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().value(HttpHeaders.ALLOW, allow -> assertThat(allow).contains("GET"))
                .expectBody().jsonPath("$.status").isEqualTo("405");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/scim/v2/Bulk", "/scim/v2/Me", "/scim/v2/Schemas", "/scim/v2/ResourceTypes",
            "/scim/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:User"})
    @DisplayName("지원하지 않는 SCIM 엔드포인트는 501 이다 — RFC 7644 §3.12, /Me 는 §3.11")
    void 지원하지_않는_엔드포인트는_501이다(String path) {
        // when, then
        client.get().uri(path).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED)
                .expectBody().jsonPath("$.status").isEqualTo("501")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("Bulk 는 POST 로 보내도 501 이다 — ServiceProviderConfig 가 지원 안 함으로 선언했다")
    void Bulk_POST_도_501이다() {
        // when, then
        client.post().uri("/scim/v2/Bulk").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED)
                .expectBody().jsonPath("$.status").isEqualTo("501");
    }

    @Test
    @DisplayName("모르는 경로는 404 이고 SCIM Error 형식이다")
    void 모르는_경로는_404다() {
        // when, then
        client.get().uri("/scim/v2/Users/kim/extra").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody().jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.status").isEqualTo("404");
    }
}

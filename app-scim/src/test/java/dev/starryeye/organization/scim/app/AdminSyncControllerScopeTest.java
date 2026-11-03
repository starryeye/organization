package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.admin.AdminExceptionHandler;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TemporaryFailureClassifier;
import dev.starryeye.organization.core.usecase.ScimRebuildMode;
import dev.starryeye.organization.core.usecase.ScimRebuildUseCase;
import dev.starryeye.organization.security.Scopes;
import dev.starryeye.organization.storage.DynamoDbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.security.web.server.context.SecurityContextServerWebExchangeWebFilter;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * SCIM 재적재의 범위 판정(설계 2026-10-28 §4.3, A4). 보안 필터체인은 {@code POST /admin/sync/**} 에 {@code admin.sync} 를 요구하고, {@code mode=wipe} 는
 * 컨트롤러가 스프링이 바꾼 모드로 보고 {@code admin.destructive} 를 더 요구한다 — 확인값 검사보다 먼저, 회차를 열기 전에.
 *
 * <p>슬라이스 시험이라 필터체인은 돌지 않는다. 컨트롤러가 인자로 받는 호출자({@code JwtAuthenticationToken})를 직접 싣는다.
 */
class AdminSyncControllerScopeTest {

    private static final String 테이블 = "organization-scim-test";
    private static final Instant 지금 = Instant.parse("2026-10-29T03:00:00Z");

    private ScimRebuildUseCase rebuild;
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        rebuild = Mockito.mock(ScimRebuildUseCase.class);
        DynamoDbProperties 속성 = new DynamoDbProperties();
        속성.setTableName(테이블);
        client = WebTestClient.bindToController(
                        new AdminSyncController(rebuild, Mockito.mock(SyncRunRepository.class), 속성))
                .controllerAdvice(new AdminExceptionHandler(TemporaryFailureClassifier.표지만()))
                // 운영에서는 보안 체인의 이 필터가 보안 컨텍스트의 인증을 exchange 의 principal 로 내놓아 컨트롤러의 Authentication 인자가 채워진다.
                // 슬라이스 시험에는 체인이 없고 mockJwt 는 Reactor Context 에만 싣으므로, 이 필터로 같은 길을 잇는다
                .webFilter(new SecurityContextServerWebExchangeWebFilter())
                // mutateWith(mockJwt()) 가 싣는 호출자를 받는 쪽 — 이것이 없으면 mutateWith 는 아무것도 하지 않는다
                .apply(springSecurity())
                .build();
    }

    /** 운영과 같은 모양의 호출자 — {@code JwtAuthenticationToken}, 권한 {@code SCOPE_<범위>} */
    private static JwtMutator 호출자(String... 범위) {
        List<GrantedAuthority> 권한 = Arrays.stream(범위)
                .<GrantedAuthority>map(이름 -> new SimpleGrantedAuthority(Scopes.권한(이름)))
                .toList();
        return mockJwt().jwt(jwt -> jwt.subject("ops")).authorities(권한);
    }

    private static SyncRun 도는실행() {
        return SyncRun.started("run-1", SyncSource.SCIM, SyncTrigger.REBUILD, 지금, "client:ops");
    }

    @Test
    @DisplayName("wipe 는 admin.destructive 가 없으면 확인값이 맞아도 403 이고 재적재를 열지 않는다")
    void wipe_는_admin_destructive_가_없으면_403_이다() {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?mode=wipe&confirm=" + 테이블).exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("mode=wipe은 admin.destructive 범위가 필요하다");
        Mockito.verifyNoInteractions(rebuild);
    }

    @Test
    @DisplayName("wipe 의 범위 판정은 확인값 검사보다 먼저다 — 범위가 없으면 확인값이 틀리거나 없어도 400 이 아니라 403 이다")
    void wipe_의_범위_판정은_확인값_검사보다_먼저다() {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?mode=wipe&confirm=틀린-이름").exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("mode=wipe은 admin.destructive 범위가 필요하다");
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?mode=wipe").exchange()
                .expectStatus().isForbidden();
        Mockito.verifyNoInteractions(rebuild);
    }

    @ParameterizedTest(name = "mode={0}")
    @ValueSource(strings = {"WIPE", "Wipe"})
    @DisplayName("모드 이름의 대소문자가 달라도 wipe 로 해석되면 같은 판정으로 막는다")
    void 대소문자가_달라도_wipe_로_보고_막는다(String 값) {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?mode=" + 값 + "&confirm=" + 테이블).exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("mode=wipe은 admin.destructive 범위가 필요하다");
        Mockito.verifyNoInteractions(rebuild);
    }

    @Test
    @DisplayName("호출자가 없으면 wipe 는 403 이다 — 판정을 건너뛰지 않는다")
    void 호출자가_없으면_wipe_는_403_이다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=wipe&confirm=" + 테이블).exchange()
                .expectStatus().isForbidden();
        Mockito.verifyNoInteractions(rebuild);
    }

    @Test
    @DisplayName("admin.destructive 가 있으면 wipe 는 확인값을 본다 — 틀리면 400, 맞으면 202")
    void admin_destructive_가_있으면_wipe_는_확인값을_본다() {
        // given
        Mockito.when(rebuild.start(ScimRebuildMode.WIPE)).thenReturn(Mono.just(도는실행()));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri("/admin/sync/rebuild?mode=wipe&confirm=틀린-이름").exchange()
                .expectStatus().isBadRequest();
        Mockito.verifyNoInteractions(rebuild);

        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri("/admin/sync/rebuild?mode=wipe&confirm=" + 테이블).exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING");
        Mockito.verify(rebuild).start(ScimRebuildMode.WIPE);
    }

    @Test
    @DisplayName("tuples 는 admin.sync 로 202 다 — admin.destructive 는 필요 없다")
    void tuples_는_admin_sync_로_202_다() {
        // given
        Mockito.when(rebuild.start(ScimRebuildMode.TUPLES)).thenReturn(Mono.just(도는실행()));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?mode=tuples").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING");
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isAccepted();
        Mockito.verify(rebuild, Mockito.times(2)).start(ScimRebuildMode.TUPLES);
    }

    @Test
    @DisplayName("알 수 없는 mode 는 400 이다 — wipe 가 아니므로 admin.destructive 를 묻지 않는다")
    void 알_수_없는_mode_는_400_이다() {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?mode=nuke").exchange()
                .expectStatus().isBadRequest();
        Mockito.verifyNoInteractions(rebuild);
    }
}

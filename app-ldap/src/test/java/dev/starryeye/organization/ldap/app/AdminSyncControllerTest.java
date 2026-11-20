package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.AdminExceptionHandler;
import dev.starryeye.organization.core.guard.ForceAllowance;
import dev.starryeye.organization.core.model.GuardHold;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TemporaryFailureClassifier;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import dev.starryeye.organization.security.Scopes;
import dev.starryeye.organization.storage.DynamoDbTemporaryFailures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.security.web.server.context.SecurityContextServerWebExchangeWebFilter;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class AdminSyncControllerTest {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");
    /** 400 문구(설계 2026-11-20 F3) */
    private static final String 허용_수가_없다 = "force=true 에는 maxDeletes(허용할 지울 줄 수, 1 이상)가 함께 와야 합니다";
    private static final String force_없는_허용_수 = "maxDeletes 는 force=true 와 함께만 씁니다";

    private FullSyncUseCase fullSync;
    private RebuildUseCase rebuild;
    private SyncRunRepository runs;
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        fullSync = Mockito.mock(FullSyncUseCase.class);
        rebuild = Mockito.mock(RebuildUseCase.class);
        runs = Mockito.mock(SyncRunRepository.class);
        client = WebTestClient.bindToController(
                        new AdminSyncController(fullSync, rebuild, runs, new SyncMetrics(new SimpleMeterRegistry())))
                .controllerAdvice(new AdminExceptionHandler(
                        new TemporaryFailureClassifier(List.of(new DynamoDbTemporaryFailures()))))
                // 운영에서는 보안 체인의 이 필터가 보안 컨텍스트의 인증을 exchange 의 principal 로 내놓아 컨트롤러의 Authentication 인자가 채워진다.
                // 슬라이스 시험에는 체인이 없고 mockJwt 는 Reactor Context 에만 싣으므로, 이 필터로 같은 길을 잇는다
                .webFilter(new SecurityContextServerWebExchangeWebFilter())
                // mutateWith(mockJwt()) 가 싣는 호출자를 받는 쪽 — 이것이 없으면 mutateWith 는 아무것도 하지 않는다
                .apply(springSecurity())
                .build();
    }

    /**
     * 컨트롤러가 받는 호출자 — 슬라이스 시험은 보안 필터체인이 없으므로 운영과 같은 모양({@code JwtAuthenticationToken}, 권한 {@code SCOPE_<범위>})을
     * 직접 싣는다. 컨트롤러는 이 값을 {@code Authentication} 인자로 받는다.
     */
    private static JwtMutator 호출자(String... 범위) {
        List<GrantedAuthority> 권한 = Arrays.stream(범위)
                .<GrantedAuthority>map(이름 -> new SimpleGrantedAuthority(Scopes.권한(이름)))
                .toList();
        return mockJwt().jwt(jwt -> jwt.subject("ops")).authorities(권한);
    }

    private static SyncRun 도는실행(SyncTrigger trigger) {
        return SyncRun.started("run-1", SyncSource.LDAP, trigger, 지금, "client:ops");
    }

    private static SyncRun 완료된실행(SyncTrigger trigger, SyncStatus status) {
        return SyncRun.builder()
                .runId("run-1")
                .source(SyncSource.LDAP)
                .trigger(trigger)
                .startedAt(지금)
                .finishedAt(지금.plusSeconds(5))
                .status(status)
                .writtenCount(12)
                .deletedCount(3)
                .failureCount(0)
                .snapshotId("20260814T030000-LDAP")
                .build();
    }

    @Test
    @DisplayName("수동 실행은 MANUAL 로 동기화를 걸고, 끝나기를 기다리지 않고 202 와 RUNNING 기록을 준다")
    void 수동_실행은_202와_RUNNING을_준다() {
        // given
        Mockito.when(fullSync.start(eq(SyncTrigger.MANUAL), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.MANUAL)));

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.runId").isEqualTo("run-1")
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.trigger").isEqualTo("MANUAL")
                .jsonPath("$.requestedBy").isEqualTo("client:ops");
    }

    @Test
    @DisplayName("요청자를 모르는 기록은 requestedBy 칸이 없다 — 속성이 없는 옛 기록")
    void 요청자를_모르면_칸이_없다() {
        // given
        Mockito.when(runs.findById("run-1"))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.SCHEDULED, SyncStatus.SUCCEEDED)));

        // when, then
        client.get().uri("/admin/sync/runs/run-1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                // jsonPath 의 doesNotExist 는 null 값도 통과시킨다 — 칸 자체가 없는지는 본문 글자로 본다
                .value(body -> assertThat(body).contains("\"runId\":\"run-1\"").doesNotContain("requestedBy"));
    }

    @Test
    @DisplayName("force=true&maxDeletes=N 이면 허용 수 입구에 N 을 넘겨 FORCED 로 건다 — 강제 회차도 N 을 상한으로 가드를 본다")
    void 강제_실행은_허용_수와_함께_FORCED로_건다() {
        // given
        Mockito.when(fullSync.startForced(eq(new ForceAllowance(12500)), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED)));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri("/admin/sync/full?force=true&maxDeletes=12500").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("FORCED");
        Mockito.verify(fullSync).startForced(eq(new ForceAllowance(12500)), any());
        Mockito.verify(fullSync, Mockito.never()).start(any(), any());
    }

    @Test
    @DisplayName("다른 인스턴스가 작업 락을 쥐고 있으면 409 다")
    void 락을_못_잡으면_409다() {
        // given
        Mockito.when(fullSync.start(any(), any()))
                .thenReturn(Mono.error(new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다")));
        Mockito.when(rebuild.start(any()))
                .thenReturn(Mono.error(new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다")));

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("Retry-After")
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").isEqualTo("다른 동기화·재적재가 진행 중입니다");
        client.post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("다른 동기화·재적재가 진행 중입니다");
    }

    @Test
    @DisplayName("실행 기록을 열지 못한 일시 장애는 503 이고 Retry-After 가 있다 — 작업은 시작하지 않았으니 다시 걸어도 된다")
    void 실행_기록을_열지_못한_일시_장애는_503_이다() {
        // given — 스로틀링 판정은 SDK 가 응답의 오류 코드로 한다 — DynamoDB 가 실제로 싣는 코드를 단다
        var 스로틀링 = ProvisionedThroughputExceededException.builder().message("처리량 초과").statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ProvisionedThroughputExceededException").build())
                .build();
        Mockito.when(fullSync.start(any(), any())).thenReturn(Mono.error(스로틀링));

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Retry-After", "10")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo("일시 장애 — 잠시 뒤 다시 시도한다");
    }

    @Test
    @DisplayName("유스케이스가 Mono 를 만들기도 전에 던져도 매달리지 않고 500 이며 예외 문구를 싣지 않는다")
    void 동기_예외는_5xx다() {
        // given
        Mockito.when(fullSync.start(any(), any())).thenThrow(new IllegalStateException("Mono 구성 전 동기 예외"));

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.detail").isEqualTo("내부 오류");
    }

    @Test
    @DisplayName("최근 실행 이력을 limit 만큼 조회한다")
    void 최근_이력을_조회한다() {
        // given
        Mockito.when(runs.findRecent(5))
                .thenReturn(Flux.just(완료된실행(SyncTrigger.SCHEDULED, SyncStatus.SUCCEEDED)));

        // when, then
        client.get().uri("/admin/sync/runs?limit=5").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].runId").isEqualTo("run-1")
                .jsonPath("$[0].trigger").isEqualTo("SCHEDULED");
    }

    private static SyncRun 멈춤_내역_있는_실행() {
        var hold = new GuardHold(3, 2, 1,
                new GuardHold.Sample(2, List.of("u1", "u2")),
                new GuardHold.Sample(0, List.of()),
                new GuardHold.Sample(1, List.of("u3")),
                new GuardHold.Sample(1, List.of("g1")),
                new GuardHold.Sample(1, List.of("g2")));
        return 완료된실행(SyncTrigger.MANUAL, SyncStatus.ABORTED).toBuilder().guardHold(hold).build();
    }

    @Test
    @DisplayName("실행 기록 하나에는 멈춤 내역이 실린다(설계 2026-11-20 §3.1)")
    void 실행_기록_하나에는_멈춤_내역이_실린다() {
        // given
        Mockito.when(runs.findById("run-1")).thenReturn(Mono.just(멈춤_내역_있는_실행()));

        // when, then
        client.get().uri("/admin/sync/runs/run-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.guardHold.lines.total").isEqualTo(6)
                .jsonPath("$.guardHold.lines.directMember").isEqualTo(3)
                .jsonPath("$.guardHold.users.gone.count").isEqualTo(2)
                .jsonPath("$.guardHold.users.gone.sample[0]").isEqualTo("u1")
                .jsonPath("$.guardHold.groups.losingParent.count").isEqualTo(1);
    }

    @Test
    @DisplayName("실행 기록 목록에는 멈춤 내역이 없다 — 한 건씩 열어 볼 때만 싣는다")
    void 실행_기록_목록에는_멈춤_내역이_없다() {
        // given
        Mockito.when(runs.findRecent(anyInt())).thenReturn(Flux.just(멈춤_내역_있는_실행()));

        // when, then
        client.get().uri("/admin/sync/runs").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"runId\":\"run-1\"").doesNotContain("guardHold"));
    }

    @Test
    @DisplayName("내역이 없는 기록에는 guardHold 칸이 없다")
    void 내역이_없는_기록에는_칸이_없다() {
        // given
        Mockito.when(runs.findById("run-1"))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.SCHEDULED, SyncStatus.SUCCEEDED)));

        // when, then
        client.get().uri("/admin/sync/runs/run-1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"runId\":\"run-1\"").doesNotContain("guardHold"));
    }

    @Test
    @DisplayName("limit 이 0 이하면 500 대신 최소값으로 보정해 조회한다")
    void limit_이_음수면_최소값으로_보정된다() {
        // given
        Mockito.when(runs.findRecent(anyInt())).thenReturn(Flux.empty());

        // when, then
        client.get().uri("/admin/sync/runs?limit=-1").exchange()
                .expectStatus().isOk();

        Mockito.verify(runs).findRecent(eq(1));
    }

    @Test
    @DisplayName("limit 이 상한을 넘으면 500 대신 상한값으로 보정해 조회한다")
    void limit_이_상한을_넘으면_상한값으로_보정된다() {
        // given
        Mockito.when(runs.findRecent(anyInt())).thenReturn(Flux.empty());

        // when, then
        client.get().uri("/admin/sync/runs?limit=100000").exchange()
                .expectStatus().isOk();

        Mockito.verify(runs).findRecent(eq(100));
    }

    @Test
    @DisplayName("실행 기록 하나를 번호로 본다")
    void 기록_하나를_본다() {
        // given
        Mockito.when(runs.findById("run-1"))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.REBUILD, SyncStatus.SUCCEEDED)));

        // when, then
        client.get().uri("/admin/sync/runs/run-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
    }

    @Test
    @DisplayName("없는 실행 기록 번호는 404 다")
    void 없는_번호는_404다() {
        // given
        Mockito.when(runs.findById("missing-run")).thenReturn(Mono.empty());

        // when, then
        client.get().uri("/admin/sync/runs/missing-run").exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("rebuild 는 가드를 지키며 재적재를 걸고 202 와 RUNNING 기록을 준다")
    void rebuild_는_가드를_지키며_건다() {
        // given
        Mockito.when(rebuild.start(any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
        Mockito.verify(rebuild).start(any());
    }

    @Test
    @DisplayName("rebuild?force=true&maxDeletes=N 은 허용 수 입구에 N 을 넘겨 건다 — ABORTED 뒤 사람이 내역을 확인하고 넘기는 통로")
    void rebuild_force는_허용_수_입구로_건다() {
        // given
        Mockito.when(rebuild.startForced(eq(new ForceAllowance(12500)), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED_REBUILD)));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri("/admin/sync/rebuild?force=true&maxDeletes=12500").exchange().expectStatus().isAccepted();
        Mockito.verify(rebuild).startForced(eq(new ForceAllowance(12500)), any());
        Mockito.verify(rebuild, Mockito.never()).start(any());
    }

    @Test
    @DisplayName("rebuild 에 mode 를 주면 400 이다 — 재적재 모드는 하나로 합쳐졌다")
    void rebuild_에_mode를_주면_400이다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(rebuild);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"force=true&maxDeletes=12500", "force=true"})
    @DisplayName("force=true 는 admin.destructive 가 없으면 403 이고 회차를 열지 않는다 — admin.sync 만으로는 안 된다. maxDeletes 가 있든 없든 범위가 먼저다")
    void force_는_admin_destructive_가_없으면_403_이고_회차를_열지_않는다(String 쿼리) {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/full?" + 쿼리).exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("force=true은 admin.destructive 범위가 필요하다");
        Mockito.verifyNoInteractions(fullSync);
    }

    @ParameterizedTest(name = "force={0}")
    @ValueSource(strings = {"yes", "1", "on"})
    @DisplayName("force=yes·1·on 도 스프링이 true 로 바꾸므로 같은 판정으로 막는다 — 쿼리 문자열을 따로 읽는 우회가 없다")
    void force_yes_도_true_로_보고_막는다(String 값) {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/full?force=" + 값).exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("force=true은 admin.destructive 범위가 필요하다");
        Mockito.verifyNoInteractions(fullSync);
    }

    @Test
    @DisplayName("admin.destructive 가 있으면 force=true(yes)&maxDeletes=N 은 202 다")
    void admin_destructive_가_있으면_force_는_202_다() {
        // given
        Mockito.when(fullSync.startForced(eq(new ForceAllowance(12500)), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED)));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri("/admin/sync/full?force=yes&maxDeletes=12500").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("FORCED");
    }

    @ParameterizedTest(name = "force={0}")
    @ValueSource(strings = {"false", "no", "0"})
    @DisplayName("force 가 꺼져 있으면 admin.sync 만으로 202 다 — MANUAL 로 건다")
    void force_가_꺼져_있으면_admin_sync_만으로_202_다(String 값) {
        // given
        Mockito.when(fullSync.start(eq(SyncTrigger.MANUAL), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.MANUAL)));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/full?force=" + 값).exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("호출자가 없으면 force=true 는 403 이다 — 판정을 건너뛰지 않는다")
    void 호출자가_없으면_force_는_403_이다() {
        // when, then
        client.post().uri("/admin/sync/full?force=true").exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("force=true은 admin.destructive 범위가 필요하다");
        Mockito.verifyNoInteractions(fullSync);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"force=true&maxDeletes=12500", "force=true"})
    @DisplayName("재적재 force=true 도 admin.destructive 가 없으면 403 이고 재적재를 열지 않는다 — maxDeletes 가 있든 없든 범위가 먼저다")
    void 재적재_force_는_admin_destructive_가_없으면_403_이고_회차를_열지_않는다(String 쿼리) {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?" + 쿼리).exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("force=true은 admin.destructive 범위가 필요하다");
        Mockito.verifyNoInteractions(rebuild);
    }

    @ParameterizedTest(name = "force={0}")
    @ValueSource(strings = {"yes", "1", "on"})
    @DisplayName("재적재도 force=yes·1·on 을 true 로 보고 막는다")
    void 재적재_force_yes_도_true_로_보고_막는다(String 값) {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild?force=" + 값).exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("force=true은 admin.destructive 범위가 필요하다");
        Mockito.verifyNoInteractions(rebuild);
    }

    @Test
    @DisplayName("재적재도 admin.destructive 가 있으면 force=true(1)&maxDeletes=N 은 202 다")
    void 재적재_admin_destructive_가_있으면_force_는_202_다() {
        // given
        Mockito.when(rebuild.startForced(eq(new ForceAllowance(12500)), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED_REBUILD)));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri("/admin/sync/rebuild?force=1&maxDeletes=12500").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING");
        Mockito.verify(rebuild).startForced(eq(new ForceAllowance(12500)), any());
    }

    @Test
    @DisplayName("재적재는 force 가 없으면 admin.sync 만으로 202 다")
    void 재적재는_force_가_없으면_admin_sync_만으로_202_다() {
        // given
        Mockito.when(rebuild.start(any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC))
                .post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isAccepted();
        Mockito.verify(rebuild).start(any());
    }

    // ---------- 확인한 수로만 넘기는 force (설계 2026-11-20 F1·F3) ----------

    /** 범위가 있는 호출자의 요청이 400 이고 정한 문구다. */
    private void 요청은_400_이다(String uri, String 문구) {
        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri(uri).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo(문구);
    }

    // 깨뜨릴 변경: maxDeletes 없는 force=true 를 받아 허용 수 없이 걸기
    @Test
    @DisplayName("동기화 force=true 에 maxDeletes 가 없으면 400 이고 동기화를 열지 않는다")
    void 동기화_force_에_허용_수가_없으면_400_이다() {
        // when, then
        요청은_400_이다("/admin/sync/full?force=true", 허용_수가_없다);
        Mockito.verifyNoInteractions(fullSync);
    }

    // 깨뜨릴 변경: maxDeletes 0 을 받기(설정의 0 은 "끔"이다)
    @Test
    @DisplayName("동기화 force=true 의 maxDeletes 가 1 미만이면 400 이고 동기화를 열지 않는다")
    void 동기화_force_의_허용_수가_1_미만이면_400_이다() {
        // when, then
        요청은_400_이다("/admin/sync/full?force=true&maxDeletes=0", 허용_수가_없다);
        Mockito.verifyNoInteractions(fullSync);
    }

    // 깨뜨릴 변경: force 없이 온 maxDeletes 를 조용히 무시하기
    @Test
    @DisplayName("동기화에 force 없이 maxDeletes 만 오면 400 이고 동기화를 열지 않는다")
    void 동기화에_force_없이_허용_수만_오면_400_이다() {
        // when, then
        요청은_400_이다("/admin/sync/full?maxDeletes=10", force_없는_허용_수);
        Mockito.verifyNoInteractions(fullSync);
    }

    // 깨뜨릴 변경: 재적재가 허용 수 검사를 하지 않기
    @Test
    @DisplayName("재적재 force=true 에 maxDeletes 가 없으면 400 이고 재적재를 열지 않는다")
    void 재적재_force_에_허용_수가_없으면_400_이다() {
        // when, then
        요청은_400_이다("/admin/sync/rebuild?force=true", 허용_수가_없다);
        Mockito.verifyNoInteractions(rebuild);
    }

    // 깨뜨릴 변경: 옛 mode 400 검사를 허용 수 검사보다 앞에 두기(검사 순서는 범위 → maxDeletes → mode, 설계 F3)
    @Test
    @DisplayName("재적재에 mode 와 force 없는 maxDeletes 가 함께 오면 maxDeletes 400 이 먼저다")
    void 재적재_허용_수_400_은_mode_400_보다_먼저다() {
        // when, then
        요청은_400_이다("/admin/sync/rebuild?mode=store&maxDeletes=10", force_없는_허용_수);
        요청은_400_이다("/admin/sync/rebuild?mode=store&force=true", 허용_수가_없다);
        Mockito.verifyNoInteractions(rebuild);
    }

    // 깨뜨릴 변경: 재적재가 force 없이 온 maxDeletes 를 조용히 무시하기
    @Test
    @DisplayName("재적재에 force 없이 maxDeletes 만 오면 400 이고 재적재를 열지 않는다")
    void 재적재에_force_없이_허용_수만_오면_400_이다() {
        // when, then
        요청은_400_이다("/admin/sync/rebuild?maxDeletes=10", force_없는_허용_수);
        Mockito.verifyNoInteractions(rebuild);
    }

    // 깨뜨릴 변경: maxDeletes 를 문자열로 받아 형식 오류를 400 이 아닌 상태로 내기
    @Test
    @DisplayName("숫자가 아닌 maxDeletes 는 스프링의 변환 실패로 400 이고 동기화를 열지 않는다")
    void 숫자가_아닌_허용_수는_400_이다() {
        // when, then
        client.mutateWith(호출자(Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE))
                .post().uri("/admin/sync/full?force=true&maxDeletes=abc").exchange()
                .expectStatus().isBadRequest();
        Mockito.verifyNoInteractions(fullSync);
    }
}

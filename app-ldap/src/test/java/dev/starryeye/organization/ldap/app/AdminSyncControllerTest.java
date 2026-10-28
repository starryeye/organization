package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.AdminExceptionHandler;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TemporaryFailureClassifier;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import dev.starryeye.organization.storage.DynamoDbTemporaryFailures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class AdminSyncControllerTest {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

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
                .build();
    }

    private static SyncRun 도는실행(SyncTrigger trigger) {
        return SyncRun.started("run-1", SyncSource.LDAP, trigger, 지금);
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
                .jsonPath("$.trigger").isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("force=true 로 요청하면 FORCED 트리거로 걸어 삭제 가드를 우회한다")
    void 강제_실행은_FORCED로_건다() {
        // given
        Mockito.when(fullSync.start(eq(SyncTrigger.FORCED), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED)));

        // when, then
        client.post().uri("/admin/sync/full?force=true").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("FORCED");
    }

    @Test
    @DisplayName("다른 인스턴스가 작업 락을 쥐고 있으면 409 다")
    void 락을_못_잡으면_409다() {
        // given
        Mockito.when(fullSync.start(any(), any()))
                .thenReturn(Mono.error(new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다")));
        Mockito.when(rebuild.start(Mockito.anyBoolean(), any()))
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
        Mockito.when(rebuild.start(eq(false), any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
        Mockito.verify(rebuild).start(eq(false), any());
    }

    @Test
    @DisplayName("rebuild?force=true 는 삭제 가드를 건너뛰고 건다 — ABORTED 뒤 사람이 확인하고 넘기는 통로")
    void rebuild_force는_가드를_건너뛴다() {
        // given
        Mockito.when(rebuild.start(eq(true), any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.post().uri("/admin/sync/rebuild?force=true").exchange().expectStatus().isAccepted();
        Mockito.verify(rebuild).start(eq(true), any());
    }

    @Test
    @DisplayName("rebuild 에 mode 를 주면 400 이다 — 재적재 모드는 하나로 합쳐졌다")
    void rebuild_에_mode를_주면_400이다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(rebuild);
    }
}

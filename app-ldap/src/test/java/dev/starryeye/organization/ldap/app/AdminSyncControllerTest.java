package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class AdminSyncControllerTest {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private FullSyncUseCase fullSync;
    private RebuildUseCase rebuild;
    private SyncRunRepository runs;
    private SyncExecutionGuard executionGuard;
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        fullSync = Mockito.mock(FullSyncUseCase.class);
        rebuild = Mockito.mock(RebuildUseCase.class);
        runs = Mockito.mock(SyncRunRepository.class);
        executionGuard = new SyncExecutionGuard();
        client = WebTestClient.bindToController(
                new AdminSyncController(fullSync, rebuild, runs, executionGuard,
                        new SyncMetrics(new SimpleMeterRegistry()))).build();
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
        Mockito.when(fullSync.start(eq(SyncTrigger.MANUAL), any(), any()))
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
        Mockito.when(fullSync.start(eq(SyncTrigger.FORCED), any(), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED)));

        // when, then
        client.post().uri("/admin/sync/full?force=true").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("FORCED");

        Mockito.verify(fullSync).start(eq(SyncTrigger.FORCED), any(), any());
    }

    @Test
    @DisplayName("동기화가 이미 진행 중이면 409 로 거절하고 걸지 않는다")
    void 중복_실행은_409로_거절한다() {
        // given
        executionGuard.tryAcquire();

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
        Mockito.verifyNoInteractions(fullSync);
    }

    @Test
    @DisplayName("202 로 답한 작업이 도는 동안 다시 걸면 409 이고, 작업이 반납하면 다시 받는다")
    void 도는_동안은_409이고_반납하면_다시_받는다() {
        // given — 반납 수단을 쥔 채 아직 끝나지 않은 작업
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Mono<Void>>> 반납수단 = ArgumentCaptor.forClass(Supplier.class);
        Mockito.when(fullSync.start(any(), 반납수단.capture(), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.MANUAL)));
        client.post().uri("/admin/sync/full").exchange().expectStatus().isAccepted();

        // when, then — 202 로 답했어도 작업은 아직 돈다
        client.post().uri("/admin/sync/full").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        client.post().uri("/admin/sync/rebuild").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);

        // when — 작업이 끝나 반납하면
        반납수단.getValue().get().block();

        // then
        client.post().uri("/admin/sync/full").exchange().expectStatus().isAccepted();
    }

    @Test
    @DisplayName("작업을 걸지 못하면(기록을 열지 못함) 5xx 이고 가드는 풀린다")
    void 걸지_못하면_가드가_풀린다() {
        // given
        Mockito.when(fullSync.start(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("DynamoDB 장애")));

        // when
        client.post().uri("/admin/sync/full").exchange().expectStatus().is5xxServerError();

        // then — 안 풀리면 이후 모든 동기화가 409 로 막힌다
        assertThat(executionGuard.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("유스케이스가 Mono 를 만들기도 전에 던져도 가드는 풀린다")
    void 동기_예외에도_가드가_풀린다() {
        // given
        Mockito.when(fullSync.start(any(), any(), any()))
                .thenThrow(new IllegalStateException("Mono 구성 전 동기 예외"));

        // when
        client.post().uri("/admin/sync/full").exchange().expectStatus().is5xxServerError();

        // then
        assertThat(executionGuard.tryAcquire()).isTrue();
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

        // when, then — Flux.take(-1) 이 조립 시점에 던지던 예외가 더 이상 나오면 안 된다
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
    @DisplayName("실행 기록 하나를 번호로 본다 — 202 로 건 작업의 결과를 보는 자리다")
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
    @DisplayName("rebuild 는 모드 없이 재적재를 걸고 202 와 RUNNING 기록을 준다")
    void rebuild_는_202다() {
        // given
        Mockito.when(rebuild.start(any(), any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
    }

    @Test
    @DisplayName("rebuild 에 mode 를 주면 400 이다 — 재적재 모드는 하나로 합쳐졌다")
    void rebuild_에_mode를_주면_400이다() {
        // when, then — 조용히 무시하면 mode=store 로 "깨끗이 비우기"를 기대한 사람이 다른 일을 받는다
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(rebuild);
        assertThat(executionGuard.tryAcquire()).as("거절한 요청은 가드를 잡지 않는다").isTrue();
    }
}

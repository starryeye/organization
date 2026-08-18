package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.RebuildMode;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

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
    @DisplayName("수동 실행은 MANUAL 트리거로 동기화하고 결과를 돌려준다")
    void 수동_실행은_MANUAL로_동작한다() {
        // given
        Mockito.when(fullSync.execute(SyncTrigger.MANUAL))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.MANUAL, SyncStatus.SUCCEEDED)));

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("MANUAL")
                .jsonPath("$.writtenCount").isEqualTo(12);
    }

    @Test
    @DisplayName("force=true 로 요청하면 FORCED 트리거로 동기화해 삭제 가드를 우회한다")
    void 강제_실행은_FORCED로_동작한다() {
        // given
        Mockito.when(fullSync.execute(SyncTrigger.FORCED))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.FORCED, SyncStatus.SUCCEEDED)));

        // when, then
        client.post().uri("/admin/sync/full?force=true").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("FORCED");

        Mockito.verify(fullSync).execute(SyncTrigger.FORCED);
    }

    @Test
    @DisplayName("가드가 발동해 중단되면 ABORTED 상태와 사유가 응답에 담긴다")
    void 중단된_결과가_사유와_함께_응답된다() {
        // given
        var aborted = 완료된실행(SyncTrigger.MANUAL, SyncStatus.ABORTED).toBuilder()
                .message("삭제 대상 412건이 임계치 30.0%를 초과했습니다")
                .build();
        Mockito.when(fullSync.execute(any())).thenReturn(Mono.just(aborted));

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ABORTED")
                .jsonPath("$.message").value(m -> assertThat((String) m).contains("임계치"));
    }

    @Test
    @DisplayName("동기화가 이미 진행 중이면 409 로 거절한다")
    void 중복_실행은_409로_거절한다() {
        // given
        executionGuard.tryAcquire();

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("동기화가 끝나면 가드가 반납되어 다시 실행할 수 있다")
    void 완료되면_가드가_반납된다() {
        // given
        Mockito.when(fullSync.execute(any()))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.MANUAL, SyncStatus.SUCCEEDED)));

        // when
        client.post().uri("/admin/sync/full").exchange().expectStatus().isOk();

        // then
        client.post().uri("/admin/sync/full").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("동기화가 예외로 끝나도 가드가 반납되어 잠기지 않는다")
    void 예외가_나도_가드가_반납된다() {
        // given
        Mockito.when(fullSync.execute(any())).thenReturn(Mono.error(new IllegalStateException("터짐")));

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
    @DisplayName("rebuild 는 mode 를 해석해 대응하는 모드로 재적재를 실행한다")
    void rebuild_는_mode_를_해석해_실행한다() {
        // given
        Mockito.when(rebuild.execute(RebuildMode.STORE))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.REBUILD, SyncStatus.SUCCEEDED)));

        // when, then
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        Mockito.verify(rebuild).execute(RebuildMode.STORE);
    }

    @Test
    @DisplayName("rebuild 에 알 수 없는 mode 를 주면 500 대신 400 으로 거절한다")
    void 알_수_없는_mode_는_400으로_거절된다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=nope").exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(rebuild);
    }
}

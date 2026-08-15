package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
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

class AdminSyncControllerTest {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private FullSyncUseCase fullSync;
    private SyncRunRepository runs;
    private SyncExecutionGuard executionGuard;
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        fullSync = Mockito.mock(FullSyncUseCase.class);
        runs = Mockito.mock(SyncRunRepository.class);
        executionGuard = new SyncExecutionGuard();
        client = WebTestClient.bindToController(
                new AdminSyncController(fullSync, null, runs, executionGuard,
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
}

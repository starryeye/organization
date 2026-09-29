package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * app-ldap 관리 API. 동기화·재적재는 겹치지 않으면 곧바로 202 와 실행 기록(RUNNING)을 주고 따로 돈다(설계 2026-09-29 §4) —
 * 결과는 {@code GET /admin/sync/runs/{runId}} 로 본다.
 */
@Slf4j
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    /** GET /admin/sync/runs 의 limit 을 이 범위로 강제한다 — 0 이하나 과도하게 큰 값을 막는다 */
    private static final int MIN_RUNS_LIMIT = 1;
    private static final int MAX_RUNS_LIMIT = 100;

    private final FullSyncUseCase fullSync;
    private final RebuildUseCase rebuild;
    private final SyncRunRepository runs;
    private final SyncExecutionGuard executionGuard;
    private final SyncMetrics metrics;

    /**
     * @param force true 면 삭제 가드를 건너뛴다. ABORTED 이후 사람이 판단해서 승인하는 통로다
     */
    @PostMapping("/full")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> full(@RequestParam(defaultValue = "false") boolean force) {
        SyncTrigger trigger = force ? SyncTrigger.FORCED : SyncTrigger.MANUAL;
        log.info("수동 전체 동기화 요청: trigger={}", trigger);
        return guarded(release -> fullSync.start(trigger, release, metrics::record));
    }

    /**
     * 전체 재적재를 건다. 모드는 하나다(설계 §3.3) — LDAP 을 다시 읽어 있어야 할 줄을 쓰고, 장부를 훑어 없어야 할 줄을 지운다.
     * 옛 {@code mode} 파라미터가 오면 400 으로 알린다 — 조용히 무시하면 {@code mode=store} 로 "깨끗이 비우기"를 기대한 사람이
     * 다른 일을 받는다.
     */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> rebuild(@RequestParam(required = false) String mode) {
        if (mode != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "재적재 모드는 하나로 합쳐졌습니다 — mode 없이 POST /admin/sync/rebuild 를 호출하세요 (받은 mode: " + mode + ")");
        }
        log.warn("전체 재적재 요청");
        return guarded(release -> rebuild.start(release, metrics::record));
    }

    @GetMapping("/runs")
    public Flux<SyncRunResponse> runs(@RequestParam(defaultValue = "20") int limit) {
        int clampedLimit = Math.max(MIN_RUNS_LIMIT, Math.min(limit, MAX_RUNS_LIMIT));
        return runs.findRecent(clampedLimit).map(SyncRunResponse::from);
    }

    /** 실행 기록 하나. 202 로 건 작업의 결과를 여기서 본다. 없으면 404 — 잘못된 번호이거나 보관 기간(30일)이 지났다. */
    @GetMapping("/runs/{runId}")
    public Mono<SyncRunResponse> run(@PathVariable String runId) {
        return runs.findById(runId)
                .map(SyncRunResponse::from)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "실행 기록이 없습니다: " + runId)));
    }

    /**
     * 겹치지 않게 가드를 잡고 작업을 건다. 못 잡으면 409. 잡았으면 반납 수단을 작업에 넘긴다 — 작업이 어떻게 끝나든 SyncJobs 가
     * 한 번 부른다. 걸기 전에 실패하면(기록을 열지 못함, 동기 예외) 여기서도 부르지만 {@link SyncExecutionGuard#releaseOnce} 라 한 번만
     * 푼다. 가드 잡기와 걸기가 같은 구독 안에서 일어나, 그 사이에 요청이 끊겨 가드만 잡힌 채 남는 틈이 없다.
     */
    private Mono<SyncRunResponse> guarded(Function<Supplier<Mono<Void>>, Mono<SyncRun>> start) {
        return Mono.defer(() -> {
            if (!executionGuard.tryAcquire()) {
                return Mono.<SyncRun>error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "동기화가 이미 진행 중입니다"));
            }
            Supplier<Mono<Void>> release = executionGuard.releaseOnce();
            return Mono.defer(() -> start.apply(release))
                    .onErrorResume(error -> release.get().then(Mono.<SyncRun>error(error)));
        }).map(SyncRunResponse::from);
    }
}

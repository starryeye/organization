package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
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

import java.util.function.Supplier;

/**
 * app-ldap 관리 API. 동기화·재적재는 작업 락을 잡으면 곧바로 202 와 실행 기록(RUNNING)을 주고 따로 돈다(설계 2026-09-29 §4) —
 * 결과는 {@code GET /admin/sync/runs/{runId}} 로 본다. 락은 클러스터 전체에서 하나라 다른 인스턴스가 쥐고 있으면 409 다(설계 2026-09-30 §3).
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
    private final SyncMetrics metrics;

    /**
     * @param force true 면 삭제 가드를 건너뛴다. ABORTED 이후 사람이 판단해서 승인하는 통로다
     */
    @PostMapping("/full")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> full(@RequestParam(defaultValue = "false") boolean force) {
        SyncTrigger trigger = force ? SyncTrigger.FORCED : SyncTrigger.MANUAL;
        log.info("수동 전체 동기화 요청: trigger={}", trigger);
        return 걸되_겹치면_409(() -> fullSync.start(trigger, metrics::record));
    }

    /**
     * 전체 재적재를 건다. 모드는 하나다 — LDAP 을 다시 읽어 있어야 할 줄을 쓰고, 장부를 훑어 없어야 할 줄을 지운다. 지울 줄이 훑은 장부의
     * 임계치를 넘으면(LDAP 이 설정 실수로 0명을 돌려주는 경우 등) 지우지 않고 ABORTED 다 — 사람이 확인한 뒤 {@code force=true} 로 넘긴다.
     * 옛 {@code mode} 파라미터가 오면 400 으로 알린다.
     */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> rebuild(@RequestParam(required = false) String mode,
                                         @RequestParam(defaultValue = "false") boolean force) {
        if (mode != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "재적재 모드는 하나로 합쳐졌습니다 — mode 없이 POST /admin/sync/rebuild 를 호출하세요 (받은 mode: " + mode + ")");
        }
        log.warn("전체 재적재 요청: force={}", force);
        return 걸되_겹치면_409(() -> rebuild.start(force, metrics::record));
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

    /** 작업 락을 못 잡았다는 것은 다른 인스턴스가 동기화·재적재 중이라는 뜻이다 — 곧바로 409, 관리자가 잠시 뒤 다시 건다. */
    private static Mono<SyncRunResponse> 걸되_겹치면_409(Supplier<Mono<SyncRun>> start) {
        return Mono.defer(start)
                .map(SyncRunResponse::from)
                .onErrorMap(LockUnavailableException.class, busy ->
                        new ResponseStatusException(HttpStatus.CONFLICT, "다른 동기화·재적재가 진행 중입니다", busy));
    }
}

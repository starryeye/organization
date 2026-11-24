package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.AdminApi;
import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.core.guard.ForceAllowance;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import dev.starryeye.organization.security.Callers;
import dev.starryeye.organization.security.Scopes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
@AdminApi
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    /** GET /admin/sync/runs 의 limit 을 이 범위로 강제한다 — 0 이하나 과도하게 큰 값을 막는다 */
    private static final int MIN_RUNS_LIMIT = 1;
    private static final int MAX_RUNS_LIMIT = 100;

    /** {@code force=true} 인데 허용 수가 하나도 없거나 준 수가 1 미만일 때의 400 문구(설계 2026-11-20 F3, 2026-11-24 R9) */
    static final String 허용_수가_없다 = "force=true 에는 maxDeletes(허용할 지울 줄 수) 또는 maxReactivations(허용할 재활성 직원 수)가 1 이상으로 함께 와야 합니다";
    /** {@code force} 없이 허용 수만 왔을 때의 400 문구(설계 2026-11-20 F3, 2026-11-24 R9) — 없는 force 에 붙은 수는 실수다. 조용히 무시하지 않는다 */
    static final String force_없는_허용_수 = "maxDeletes·maxReactivations 는 force=true 와 함께만 씁니다";

    private final FullSyncUseCase fullSync;
    private final RebuildUseCase rebuild;
    private final SyncRunRepository runs;
    private final SyncMetrics metrics;

    /**
     * @param force      true 면 강제 동기화(FORCED)다 — ABORTED 이후 사람이 실행 기록의 {@code guardHold} 를 보고 승인하는 통로다. 가드를 건너뛰지 않고
     *                   비율 없이 {@code maxDeletes} 를 상한으로 같은 가드를 본다(설계 2026-11-20 F1·F2). {@code admin.sync} 에 더해
     *                   {@code admin.destructive} 가 있어야 한다
     * @param maxDeletes 허용할 지울 줄 수 N — {@code force=true} 와 함께만, 1 이상으로 온다. 아니면 400 이다(F3). 범위 검사(403)가 먼저다
     * @param maxReactivations 허용할 재활성 직원 수 M — {@code maxDeletes} 와 같은 규칙이다. 둘 중 하나는 있어야 한다(설계 2026-11-24 R9)
     * @param caller     토큰의 호출자. 보안 필터가 {@code admin.sync} 를 본 뒤에 오며, {@code force} 의 추가 범위는 여기서 본다
     */
    @PostMapping("/full")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> full(@RequestParam(defaultValue = "false") boolean force,
                                      @RequestParam(required = false) Integer maxDeletes,
                                      @RequestParam(required = false) Integer maxReactivations,
                                      Authentication caller) {
        ForceAllowance allowance = 허용_수를_정한다(force, maxDeletes, maxReactivations, caller);
        SyncTrigger trigger = allowance != null ? SyncTrigger.FORCED : SyncTrigger.MANUAL;
        log.info("수동 전체 동기화 요청: trigger={} maxDeletes={} maxReactivations={}", trigger, maxDeletes, maxReactivations);
        return 걸되_겹치면_409(() -> allowance != null
                ? fullSync.startForced(allowance, metrics::record)
                : fullSync.start(trigger, metrics::record));
    }

    /**
     * 전체 재적재를 건다. 모드는 하나다 — LDAP 을 다시 읽어 있어야 할 줄을 쓰고, 장부를 훑어 없어야 할 줄을 지운다. 지울 줄이 훑은 장부의
     * 임계치 또는 상한을 넘으면(검색 베이스·필터 실수로 디렉터리의 큰 부분이 빠진 경우 등) ABORTED 다 — 사람이 실행 기록의 {@code guardHold} 를 확인한 뒤
     * {@code force=true&maxDeletes=N} 으로 건다. 강제 재적재도 가드를 건너뛰지 않고 비율 없이 N 을 상한으로 같은 가드를 본다(설계 2026-11-20 F1·F2). LDAP 이
     * 사람 소속이 하나도 없는 그림(0명 등)을 주면 가드까지 가지 않고 데이터 오류로 FAILED 이고, {@code force} 로도 넘기지 못한다(설계 2026-10-27 §13).
     * 가드가 걸려도 디렉터리가
     * 막았다고 명시한 회수(비활성 직원의 줄)를 따로 떼어 같은 가드로 보아, 그 회수만으로 가드 안이면 그것만 지우고 나머지는 보류한다(설계 2026-10-20 §5.4).
     * 그 회수가 없거나 그것도 가드를 넘으면 아무것도 지우지 않는다. 옛 {@code mode} 파라미터가 오면 400 으로 알린다.
     * {@code force=true} 는 {@code admin.sync} 에 더해 {@code admin.destructive} 가 있어야 한다 — 없으면 무엇보다 먼저 403 이다. 검사 순서는 범위(403) →
     * 두 수(400) → {@code mode}(400)다(F3, 설계 2026-11-24 R10). 재활성 가드가 보류한 회차는 {@code force=true&maxReactivations=M} 으로 넘긴다.
     */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> rebuild(@RequestParam(required = false) String mode,
                                         @RequestParam(defaultValue = "false") boolean force,
                                         @RequestParam(required = false) Integer maxDeletes,
                                         @RequestParam(required = false) Integer maxReactivations,
                                         Authentication caller) {
        ForceAllowance allowance = 허용_수를_정한다(force, maxDeletes, maxReactivations, caller);
        if (mode != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "재적재 모드는 하나로 합쳐졌습니다 — mode 없이 POST /admin/sync/rebuild 를 호출하세요 (받은 mode: " + mode + ")");
        }
        log.warn("전체 재적재 요청: force={} maxDeletes={} maxReactivations={}", force, maxDeletes, maxReactivations);
        return 걸되_겹치면_409(() -> allowance != null
                ? rebuild.startForced(allowance, metrics::record)
                : rebuild.start(metrics::record));
    }

    @GetMapping("/runs")
    public Flux<SyncRunResponse> runs(@RequestParam(defaultValue = "20") int limit) {
        int clampedLimit = Math.max(MIN_RUNS_LIMIT, Math.min(limit, MAX_RUNS_LIMIT));
        return runs.findRecent(clampedLimit).map(SyncRunResponse::listed);
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
     * 요청의 허용 수를 정한다(설계 2026-11-20 F3). 강제가 아니면 null 이다. 순서:
     * <ol>
     *   <li>{@code force=true} 면 범위({@code admin.destructive})를 본다 — 없으면 403({@link #요구한다_force}). 두 수보다 먼저다.</li>
     *   <li>{@code force=true} 인데 {@code maxDeletes}·{@code maxReactivations} 가 하나도 없거나, 준 수가 1 미만이면 400 — 설정의 {@code 0} 은 "끔"이라
     *       요청의 0 을 받으면 같은 수가 두 뜻이 된다. 한쪽만 줘도 되고(안 준 쪽은 허용 0), 둘 다 줘도 된다(설계 2026-11-24 R9).</li>
     *   <li>{@code force} 없이 어느 수든 오면 400 — 없는 force 에 붙은 수는 실수다.</li>
     * </ol>
     * 숫자가 아닌 두 수는 여기까지 오지 않는다 — 스프링의 변환 실패로 400 이다(설계 §3.2 판단, 지금의 {@code force=abc} 와 같다).
     * 회차를 열거나 락을 잡는 어떤 일보다 앞에서 부른다.
     */
    private static ForceAllowance 허용_수를_정한다(boolean force, Integer maxDeletes, Integer maxReactivations,
                                                  Authentication caller) {
        요구한다_force(force, caller);
        if (!force) {
            if (maxDeletes != null || maxReactivations != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, force_없는_허용_수);
            }
            return null;
        }
        if ((maxDeletes == null && maxReactivations == null)
                || (maxDeletes != null && maxDeletes < 1)
                || (maxReactivations != null && maxReactivations < 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 허용_수가_없다);
        }
        return new ForceAllowance(maxDeletes, maxReactivations);
    }

    /**
     * {@code force=true} 는 운영자가 정한 상한으로 삭제 가드를 바꾸는 위험한 요청이라 {@code admin.destructive} 를 더 요구한다(설계 2026-10-28 §4.3, A4,
     * 2026-11-20 F1). 필터가 쿼리 문자열을 따로 읽지 않고 스프링이 바꾼 {@code boolean} 으로 판정하므로 {@code force=yes}·{@code 1} 도 같은 길이다.
     * 회차를 열거나 락을 잡는 어떤 일보다 앞에서 부른다.
     */
    private static void 요구한다_force(boolean force, Authentication caller) {
        if (force) {
            Callers.요구한다(caller, Scopes.ADMIN_DESTRUCTIVE, "force=true");
        }
    }

    /** 작업 락을 못 잡았다는 것은 다른 인스턴스가 동기화·재적재 중이라는 뜻이다 — 곧바로 409, 관리자가 잠시 뒤 다시 건다. */
    private static Mono<SyncRunResponse> 걸되_겹치면_409(Supplier<Mono<SyncRun>> start) {
        return Mono.defer(start)
                .map(SyncRunResponse::from)
                .onErrorMap(LockUnavailableException.class, busy ->
                        new ResponseStatusException(HttpStatus.CONFLICT, "다른 동기화·재적재가 진행 중입니다", busy));
    }
}

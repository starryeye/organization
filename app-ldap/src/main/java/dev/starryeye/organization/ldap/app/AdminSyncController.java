package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
// TASK-16: RebuildUseCase 와 RebuildMode 는 Task 16에서 만든다. 그때 아래 두 임포트를 복원한다.
// import dev.starryeye.organization.core.usecase.RebuildMode;
// import dev.starryeye.organization.core.usecase.RebuildUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    private final FullSyncUseCase fullSync;
    // TASK-16: 타입을 RebuildUseCase 로 복원한다. 그 전까지는 생성자 자리만 맞추는 자리표시자다.
    private final Object rebuild;
    private final SyncRunRepository runs;
    private final SyncExecutionGuard executionGuard;

    /**
     * @param force true 면 삭제 가드를 건너뛴다. ABORTED 이후 사람이 판단해서 승인하는 통로다
     */
    @PostMapping("/full")
    public Mono<SyncRunResponse> full(@RequestParam(defaultValue = "false") boolean force) {
        SyncTrigger trigger = force ? SyncTrigger.FORCED : SyncTrigger.MANUAL;
        log.info("수동 전체 동기화 요청: trigger={}", trigger);
        return guarded(fullSync.execute(trigger));
    }

    // TASK-16: RebuildUseCase 가 만들어지면 이 메서드를 복원한다.
    // /**
    //  * @param mode snapshot(기본) 또는 store. 각각의 한계는 설계 문서 §8.2, §8.3 참고
    //  */
    // @PostMapping("/rebuild")
    // public Mono<SyncRunResponse> rebuild(@RequestParam(defaultValue = "snapshot") String mode) {
    //     RebuildMode rebuildMode = RebuildMode.from(mode);
    //     log.warn("전체 재적재 요청: mode={}", rebuildMode);
    //     return guarded(rebuild.execute(rebuildMode));
    // }

    @GetMapping("/runs")
    public Flux<SyncRunResponse> runs(@RequestParam(defaultValue = "20") int limit) {
        return runs.findRecent(limit).map(SyncRunResponse::from);
    }

    /** 동기화가 겹쳐 돌지 않게 감싼다. 어떤 경로로 끝나든 반드시 반납한다. */
    private Mono<SyncRunResponse> guarded(Mono<SyncRun> action) {
        if (!executionGuard.tryAcquire()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT, "동기화가 이미 진행 중입니다"));
        }
        return action
                .map(SyncRunResponse::from)
                .doFinally(signal -> executionGuard.release());
    }
}

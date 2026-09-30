package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SyncRunRepository {

    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger);

    /** 완료된 SyncRun 을 반환한다. 관리 API 가 이 값을 응답으로 쓴다. */
    Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome);

    Flux<SyncRun> findRecent(int limit);

    /**
     * 실행 기록 하나. 없으면 빈 Mono.
     *
     * <p>관리 API {@code GET /admin/sync/runs/{runId}} 가 쓴다 — 재적재·수동 동기화는 202 로 곧바로 답하고, 결과는 이것으로 본다
     * (설계 2026-09-29 §4). 보관 기간({@code syncrun-retention-days})이 지난 기록은 없다.
     */
    Mono<SyncRun> findById(String runId);
}

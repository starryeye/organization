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
}

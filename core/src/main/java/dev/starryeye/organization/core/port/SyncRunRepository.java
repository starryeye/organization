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
     * 이번 달·지난달 파티션에서 RUNNING 인 기록을 모두, 최신순으로 준다(설계 2026-10-27 §12). 죽은 기록을 닫는 쪽
     * ({@code StaleRuns.닫는다})이 쓴다 — 최근 N건만 보면 죽은 기록 뒤로 끝난 기록이 쌓여 영영 닫히지 않는다.
     *
     * <p><b>비용.</b> 두 파티션의 Query 이고 Scan 이 아니다. 거름식(status = RUNNING)은 읽은 뒤에 걸러 읽기 용량은 파티션 전체 크기만큼 든다 — app-ldap 은
     * 매시 회차마다 두 달치(약 1,500건) 를 읽는다. 아이템이 작아 용량은 수십 단위다.
     */
    Flux<SyncRun> findRunning();

    /**
     * 실행 기록 하나. 없으면 빈 Mono.
     *
     * <p>관리 API {@code GET /admin/sync/runs/{runId}} 가 쓴다 — 재적재·수동 동기화는 202 로 곧바로 답하고, 결과는 이것으로 본다
     * (설계 2026-09-29 §4). 보관 기간({@code syncrun-retention-days})이 지난 기록은 없다.
     */
    Mono<SyncRun> findById(String runId);

    /**
     * 락을 잡은 다음 작업이 죽은 기록을 닫을 때 쓴다(설계 2026-09-30 §3.3). 저장된 기록이 <b>아직 RUNNING 일 때만</b>
     * FAILED({@code reason}) 로 덮어쓴다. 이미 끝났으면 빈 Mono — 아무것도 바꾸지 않는다.
     *
     * <p><b>왜 조건이 필요한가.</b> 앞 작업이 락을 반납한 뒤·자기 기록을 쓰기 전의 틈에 다음 작업이 락을 잡고 이 기록을 볼 수 있다 —
     * 그 순간엔 방금 끝난 기록도 아직 RUNNING 으로 보인다(읽기는 최종 일관성). 조건이 있으면 어느 쪽 쓰기가 먼저 닿든 결과는 맞다:
     * 이 닫기가 먼저 닿으면 앞 작업의 조건 없는 {@link #finish} 가 그 위에 실제 결과를 덮어쓰고, {@link #finish} 가 먼저 닿으면
     * 이 조건이 깨져 닫기는 아무 일도 하지 않는다.
     */
    Mono<SyncRun> abandon(SyncRun run, String reason);
}

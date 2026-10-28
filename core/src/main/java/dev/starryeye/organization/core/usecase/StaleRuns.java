package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * 죽은 RUNNING 실행 기록을 닫는 일 (설계 2026-10-27 §12). 기록이 RUNNING 으로 남는 길은 둘이다 — 락을 쥔 작업이 도중에 죽거나(강제 종료·정전), 락을 잡지 않는
 * 아카이빙이 죽거나. 닫는 쪽은 규칙이 둘이고 <b>부르는 쪽이 사유 함수로 정한다</b>({@link #닫는다}).
 * <ul>
 *   <li>락을 쥔 회차({@link SyncJobs})는 같은 source 의 ARCHIVE 가 아닌 RUNNING 을 나이와 상관없이 닫는다 — 락을 쥐었으니 그것들은 죽은 작업이다.</li>
 *   <li>누구든 시작할 때(동기화·재적재·아카이빙) 시작한 지 {@code sync.job-timeout} + {@link #여유} 가 지난 RUNNING 은 종류와 상관없이 닫는다 —
 *       작업은 기한을 넘기면 스스로 FAILED 로 끝나므로 그 뒤에도 RUNNING 이면 죽은 것이다. 락을 잡지 않는 아카이빙의 기록은 이 규칙으로만 닫힌다.</li>
 * </ul>
 */
@Slf4j
final class StaleRuns {

    /** 기한이 지난 뒤에도 기록을 닫지 않고 더 기다리는 시간 — 기한이 막 끝난 작업이 제 기록을 쓸 틈을 준다. */
    static final Duration 여유 = Duration.ofMinutes(10);

    static final String 기한_경과_사유 = "비정상 종료로 중단(기한 + 10분 경과)";

    private StaleRuns() {
    }

    /** 시작한 지 {@code jobTimeout + 여유} 를 넘겼으면 오래된 것이다. 딱 그만큼이면 아직이다. */
    static boolean 오래됐다(SyncRun run, Instant now, Duration jobTimeout) {
        return run.startedAt().isBefore(now.minus(jobTimeout.plus(여유)));
    }

    /**
     * RUNNING 기록 가운데 {@code 사유} 가 null 이 아닌 것을 그 사유로 닫는다. 방금 연 자기 기록({@code 지금_기록})은 건드리지 않는다. 찾거나 닫다 실패해도
     * 오류를 삼킨다 — 기록 정리는 부가 일이라 이것 때문에 작업이 실패하면 안 된다.
     *
     * <p>{@link SyncRunRepository#abandon} 은 저장된 기록이 <b>아직 RUNNING 일 때만</b> 닫는다. 앞 작업이 락을 반납한 뒤·자기
     * 기록을 쓰기 전의 틈에 여기까지 왔으면, 방금 SUCCEEDED 로 끝난 기록이 이 목록에는 아직 RUNNING 으로 보일 수 있다(읽기는 최종
     * 일관성) — 조건이 없으면 그 결과·집계값을 지울 뻔한다. {@code abandon} 이 빈 Mono 를 주면 이미 끝나 있었다는 뜻이니 경고할 일이
     * 아니다. 나이로 잘못 닫았더라도(느리게 살아 있던 작업) 그 작업의 늦은 {@code finish} 가 조건 없이 덮어쓴다.
     *
     * @param 사유 닫을 이유. null 이면 그 기록은 두고 간다
     */
    static Mono<Void> 닫는다(SyncRunRepository runs, String 지금_기록, Function<SyncRun, String> 사유) {
        return runs.findRunning()
                .filter(run -> !run.runId().equals(지금_기록))
                .concatMap(run -> {
                    String 이유 = 사유.apply(run);
                    if (이유 == null) {
                        return Mono.empty();
                    }
                    return runs.abandon(run, 이유)
                            .doOnSuccess(closed -> {
                                if (closed != null) {
                                    log.warn("[{}] 끝나지 못한 채 남은 실행 기록을 닫았다: {}", closed.runId(), 이유);
                                } else {
                                    log.debug("[{}] 닫으려 했으나 이미 끝나 있었다 — 반납과 기록 사이의 틈", run.runId());
                                }
                            })
                            .onErrorResume(error -> {
                                log.warn("[{}] 끝나지 못한 실행 기록을 닫지 못했다", run.runId(), error);
                                return Mono.empty();
                            });
                })
                .then()
                .onErrorResume(error -> {
                    log.warn("끝나지 못한 실행 기록을 찾지 못했다 — 작업은 계속한다", error);
                    return Mono.empty();
                });
    }
}

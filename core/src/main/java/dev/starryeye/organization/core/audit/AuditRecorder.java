package dev.starryeye.organization.core.audit;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.port.AuditLog;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 사건 하나를 이력에 남긴다(설계 2026-10-10 §4, 2026-10-15 §3). 락 안의 일이 끝난 뒤, 락을 반납한 뒤에 부른다.
 *
 * <p><b>시각은 부르는 쪽이 락 안의 일이 끝난 때 찍는다(§4.4).</b> 락 안의 일이 끝난 때(커밋 직후, 또는 실패·취소로 끝난 때) {@link #now()} 로
 * 잡아 두고, 반납한 뒤 쓸 때 넘긴다. 부르는 쪽은 {@code SyncJobs} 와 SCIM 요청이다. 쓰는 때에 찍으면 반납 뒤의 일(스냅샷 저장, 10만 줄 만들기)만큼
 * 늦어져, 그 사이 락을 잡고 커밋한 SCIM 쓰기보다 늦은 시각이 된다 — 최신순 이력이 뒤집힌다.
 *
 * <p><b>오류로 끝나지 않는다(§4.5).</b> 변경은 이미 일어났고(실패한 회차·요청이 한 변경 포함), 작업을 실패시켜도 재시도(IdP 의 다시 보내기, 다음 회차)는
 * 그것을 다시 만들지 못한다. 그래서 빠진 줄 수를 돌려주고, ERROR 로그와 지표({@link AuditObserver})로 크게 알린다. 회차라면 {@link #recordRun} 이 회차
 * 메시지에도 붙인다.
 */
@Slf4j
public final class AuditRecorder {

    /** 아무것도 남기지 않는다 — 이력과 무관한 테스트와 옛 생성자가 쓴다. */
    public static final AuditRecorder NONE = new AuditRecorder(AuditLog.NONE, AuditObserver.NOOP, Clock.systemUTC());

    static final String 실패_메모 = "감사 기록 %d건 실패";

    private final AuditLog auditLog;
    private final AuditObserver observer;
    private final Clock clock;

    public AuditRecorder(AuditLog auditLog, AuditObserver observer, Clock clock) {
        this.auditLog = auditLog;
        this.observer = observer;
        this.clock = clock;
    }

    /**
     * 지금(서버 시계) — 부르는 쪽({@code SyncJobs}·SCIM 요청)이 락 안의 일이 끝난 때(커밋 직후, 또는 실패·취소로 끝난 때) 잡아
     * {@link #record}·{@link #recordRun} 에 넘긴다(§4.4).
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * 사건 하나를 남긴다. 남길 것이 없는 줄은 빼고, 하나도 없으면 쓰지 않는다 — 같은 조직도를 다시 동기화하면 쓰기가 0번이다(§3.6).
     *
     * @param at 사건의 시각 — 부르는 쪽이 락 안의 일이 끝난 때 {@link #now()} 로 잡은 것이다. 쓰는 것은 반납한 뒤다
     * @return 쓰지 못한 줄 수. 0 이면 다 썼다. 저장소가 오류로 끝나면 남기려던 줄 전부다
     */
    public Mono<Integer> record(AuditCause cause, Instant at, List<AuditEntry> entries) {
        return Mono.defer(() -> {
            List<AuditEntry> 남길것 = entries.stream().filter(entry -> !entry.isEmpty()).toList();
            if (남길것.isEmpty()) {
                return Mono.just(0);
            }
            AuditEvent event = new AuditEvent(at, cause, 남길것);
            return Mono.defer(() -> auditLog.write(event))
                    .defaultIfEmpty(0)
                    .doOnNext(빠진 -> {
                        if (빠진 > 0) {
                            log.error("감사 기록 {}줄을 쓰지 못했다 — 원인 {}/{}/{}", 빠진, cause.source(), cause.kind(), cause.id());
                        }
                    })
                    .onErrorResume(error -> {
                        log.error("감사 기록 {}줄을 쓰지 못했다 — 원인 {}/{}/{}", 남길것.size(), cause.source(), cause.kind(),
                                cause.id(), error);
                        return Mono.just(남길것.size());
                    })
                    .doOnNext(빠진 -> {
                        if (빠진 > 0) {
                            알린다(빠진, cause);
                        }
                    });
        });
    }

    /** 지표로 알린다. 지표가 던져도 빠진 줄 수는 그대로 돌려준다 — 이력 단계는 오류로 끝나지 않는다(§4.5). */
    private void 알린다(int 빠진, AuditCause cause) {
        try {
            observer.writeFailed(빠진);
        } catch (RuntimeException error) {
            log.error("감사 기록 실패 {}줄을 지표로 알리지 못했다 — 원인 {}/{}/{}", 빠진, cause.source(), cause.kind(), cause.id(), error);
        }
    }

    /**
     * 회차의 마무리 — 남긴 뒤 빠진 줄이 있으면 회차 메시지 앞에 붙인다(§4.5). 회차의 상태와 집계는 그대로다.
     *
     * @param at 회차의 락 안의 일이 끝난 때 {@link #now()} 로 잡은 시각. 쓰는 것은 반납한 뒤다
     */
    public Mono<SyncOutcome> recordRun(AuditCause cause, Instant at, List<AuditEntry> entries, SyncOutcome outcome) {
        return record(cause, at, entries)
                .map(빠진 -> 빠진 > 0 ? outcome.withNote(실패_메모.formatted(빠진)) : outcome);
    }
}

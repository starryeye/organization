package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncStatus;
import io.micrometer.core.instrument.MeterRegistry;
import dev.starryeye.organization.core.usecase.LockObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SyncRun 하나를 Micrometer 지표로 남긴다. 스케줄러와 관리 컨트롤러 양쪽에서 호출한다. 작업 락에서 일어난 일도 알림받아 남긴다(설계 2026-11-20 L1).
 *
 * <table>
 *   <caption>락 지표</caption>
 *   <tr><td>{@code sync.lock.wait}</td><td>Timer</td><td>–</td></tr>
 *   <tr><td>{@code sync.lock.contended}</td><td>Counter</td><td>–</td></tr>
 *   <tr><td>{@code sync.lock.lease_lost}</td><td>Counter</td><td>–</td></tr>
 * </table>
 *
 * <p>{@code contended} 는 다른 쪽이 락을 쥐어 조건 실패로 밀린 획득만 센다. app-ldap 을 둘 이상 띄운 배포에서는 매시 (인스턴스 수 − 1)만큼
 * 오르는 것이 정상이다 — 경보는 이 값의 절대 크기가 아니라 {@code lease_lost} 와 변화를 본다.
 *
 * <p>두 카운터는 기동 때 0 으로 만든다 — 첫 경합·상실 때 계열을 만들면 Prometheus {@code increase()} 가 그 첫 값을 증가로 세지 않는다.
 */
@Slf4j
@Component
public class SyncMetrics implements LockObserver {

    private final MeterRegistry registry;

    public SyncMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.counter("sync.lock.contended");
        registry.counter("sync.lock.lease_lost");
    }

    @Override
    public void acquireFinished(Duration waited, boolean contended) {
        registry.timer("sync.lock.wait").record(waited);
        if (contended) {
            registry.counter("sync.lock.contended").increment();
        }
    }

    @Override
    public void leaseLost(String reason) {
        log.warn("작업 락 리스를 잃었다: {}", reason);
        registry.counter("sync.lock.lease_lost").increment();
    }

    public void record(SyncRun run) {
        String source = run.source().name();

        // 아직 끝나지 않은 실행은 소요 시간도, 튜플 집계도 확정되지 않았다.
        // 지금은 미완료 실행의 카운트가 0 이라 더해도 값이 안 바뀌지만, 그건 우연이다 —
        // 부분 집계를 중간에 노출하게 되는 순간 조용히 이중 계상이 된다.
        if (run.finishedAt() == null) {
            return;
        }

        registry.timer("sync.duration",
                        "source", source,
                        "trigger", run.trigger().name(),
                        "status", run.status().name())
                .record(Duration.between(run.startedAt(), run.finishedAt()));

        registry.counter("sync.tuples.written", "source", source).increment(run.writtenCount());
        registry.counter("sync.tuples.deleted", "source", source).increment(run.deletedCount());
        registry.counter("sync.tuples.failed", "source", source).increment(run.failureCount());

        if (run.status() == SyncStatus.ABORTED) {
            registry.counter("sync.guard.aborted").increment();
        }
    }
}

package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SyncRun 하나를 Micrometer 지표로 남긴다. 스케줄러와 관리 컨트롤러 양쪽에서 호출한다.
 */
@Component
@RequiredArgsConstructor
public class SyncMetrics {

    private final MeterRegistry registry;

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

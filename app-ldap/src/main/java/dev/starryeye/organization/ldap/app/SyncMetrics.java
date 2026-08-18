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

        if (run.finishedAt() != null) {
            registry.timer("sync.duration",
                            "source", source,
                            "trigger", run.trigger().name(),
                            "status", run.status().name())
                    .record(Duration.between(run.startedAt(), run.finishedAt()));
        }

        registry.counter("sync.tuples.written", "source", source).increment(run.writtenCount());
        registry.counter("sync.tuples.deleted", "source", source).increment(run.deletedCount());
        registry.counter("sync.tuples.failed", "source", source).increment(run.failureCount());

        if (run.status() == SyncStatus.ABORTED) {
            registry.counter("sync.guard.aborted").increment();
        }
    }
}

package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SyncMetricsTest {

    private static final Instant 시작 = Instant.parse("2026-08-14T03:00:00Z");

    private SimpleMeterRegistry registry;
    private SyncMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SyncMetrics(registry);
    }

    private static SyncRun 실행(SyncStatus status, int written, int deleted, int failed) {
        return SyncRun.builder()
                .runId("run-1")
                .source(SyncSource.LDAP)
                .trigger(SyncTrigger.SCHEDULED)
                .startedAt(시작)
                .finishedAt(시작.plusSeconds(12))
                .status(status)
                .writtenCount(written)
                .deletedCount(deleted)
                .failureCount(failed)
                .build();
    }

    @Test
    @DisplayName("동기화 소요 시간이 소스·트리거·상태 태그와 함께 기록된다")
    void 소요_시간이_기록된다() {
        // given, when
        metrics.record(실행(SyncStatus.SUCCEEDED, 10, 2, 0));

        // then
        var timer = registry.find("sync.duration")
                .tag("source", "LDAP").tag("trigger", "SCHEDULED").tag("status", "SUCCEEDED")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("생성·삭제·실패 튜플 수가 각각 카운터에 누적된다")
    void 튜플_카운터가_누적된다() {
        // given, when
        metrics.record(실행(SyncStatus.PARTIAL, 10, 2, 3));
        metrics.record(실행(SyncStatus.SUCCEEDED, 5, 1, 0));

        // then
        assertThat(registry.find("sync.tuples.written").tag("source", "LDAP").counter().count())
                .isEqualTo(15.0);
        assertThat(registry.find("sync.tuples.deleted").tag("source", "LDAP").counter().count())
                .isEqualTo(3.0);
        assertThat(registry.find("sync.tuples.failed").tag("source", "LDAP").counter().count())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("가드가 발동한 실행은 별도 카운터로 집계된다")
    void 가드_발동이_집계된다() {
        // given, when
        metrics.record(실행(SyncStatus.ABORTED, 0, 0, 0));
        metrics.record(실행(SyncStatus.SUCCEEDED, 5, 0, 0));

        // then
        assertThat(registry.find("sync.guard.aborted").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("완료 시각이 없는 실행은 소요 시간을 기록하지 않는다")
    void 미완료_실행은_시간을_기록하지_않는다() {
        // given
        var running = SyncRun.started("run-2", SyncSource.LDAP, SyncTrigger.MANUAL, 시작);

        // when
        metrics.record(running);

        // then
        assertThat(registry.find("sync.duration").timers()).isEmpty();
    }
}

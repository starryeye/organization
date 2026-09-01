package dev.starryeye.organization.scim.app;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 §7 이 요구한 지표 넷이 실제로 나가는지 본다.
 *
 * <p>{@code scim.lock.lease_lost} 가 특히 중요하다 — 리스를 잃는 세 갈래는 어느 것도 응답에
 * 나타나지 않아, 이 카운터가 없으면 로그를 사람이 읽을 때까지 아무도 모른다.
 */
class ScimSyncMetricsTest {

    private SimpleMeterRegistry registry;
    private ScimSyncMetrics metrics;

    @BeforeEach
    void 준비한다() {
        registry = new SimpleMeterRegistry();
        metrics = new ScimSyncMetrics(registry);
    }

    @Test
    @DisplayName("어긋남은 kind 태그를 달고 발견한 건수만큼 오른다")
    void 어긋남을_센다() {
        // when
        metrics.observed(2, 3);

        // then
        assertThat(registry.counter("scim.drift.detected", "kind", "extra").count()).isEqualTo(2);
        assertThat(registry.counter("scim.drift.detected", "kind", "missing").count()).isEqualTo(3);
    }

    @Test
    @DisplayName("락 대기 시간은 타이머로, 경합은 카운터로 남는다")
    void 대기와_경합을_센다() {
        // when — 경합 없이 한 번, 밀려서 한 번
        metrics.acquireFinished(Duration.ofMillis(5), false);
        metrics.acquireFinished(Duration.ofMillis(450), true);

        // then
        assertThat(registry.timer("scim.lock.wait").count())
                .as("두 시도 모두 실제로 기다린 시간이 있다")
                .isEqualTo(2);
        assertThat(registry.counter("scim.lock.contended").count())
                .as("밀린 것만 경합이다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("리스 상실은 이유와 무관하게 한 카운터로 모인다")
    void 리스_상실을_센다() {
        // when — 설계 §7 의 표에는 태그가 없다. 자유 문자열을 태그로 쓰면 카디널리티가 터진다.
        metrics.leaseLost("재적재 도중 리스 상실");
        metrics.leaseLost("반납 실패");

        // then
        assertThat(registry.counter("scim.lock.lease_lost").count()).isEqualTo(2);
    }
}

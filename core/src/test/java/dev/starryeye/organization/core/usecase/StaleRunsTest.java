package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 죽은 기록의 나이(설계 2026-10-27 §12) — 시작한 지 {@code sync.job-timeout} + 10분이 지나야 한다.
 */
class StaleRunsTest {

    private static final Instant 지금 = Instant.parse("2026-10-28T03:00:00Z");
    private static final Duration 기한 = Duration.ofMinutes(30);

    private static SyncRun 분_전에_시작한(long 분) {
        return SyncRun.started("r", SyncSource.SCIM, SyncTrigger.REBUILD, 지금.minus(Duration.ofMinutes(분)));
    }

    @Test
    @DisplayName("기한 + 10분이 딱 찬 기록은 아직 오래되지 않았다 — 넘어야 오래됐다")
    void 경계는_넘어야_오래됐다() {
        // given, when, then
        assertThat(StaleRuns.오래됐다(분_전에_시작한(40), 지금, 기한)).isFalse();
        assertThat(StaleRuns.오래됐다(분_전에_시작한(41), 지금, 기한)).isTrue();
    }

    @Test
    @DisplayName("기한을 넘기지 않은 기록은 오래되지 않았다")
    void 최근_기록은_오래되지_않았다() {
        // given, when, then
        assertThat(StaleRuns.오래됐다(분_전에_시작한(5), 지금, 기한)).isFalse();
    }
}

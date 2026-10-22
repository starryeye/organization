package dev.starryeye.organization.scim.app;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 §7 이 요구한 지표 넷이 실제로 나가는지 본다.
 *
 * <p>{@code scim.lock.lease_lost} 가 특히 중요하다 — 리스를 잃는 갈래(작업·쓰기 도중 갱신 실패, 쓰기 직전·커밋 직전 재확인 실패,
 * 반납 실패)는 응답에 흔적이 없거나(반납 실패) 503 뿐이어서, 이 카운터가 없으면 로그를 사람이 읽을 때까지 아무도 모른다.
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
    @DisplayName("아카이빙 어긋남은 kind 태그를 단 게이지에 마지막 값으로 남는다 — 누적하지 않고, 0 으로도 내려온다(설계 2026-10-20 §6.4)")
    void 아카이빙_어긋남을_게이지로_낸다() {
        // given — 아직 아카이빙을 하지 않았다
        assertThat(registry.get("scim.archive.drift").tag("kind", "extra").gauge().value()).isZero();
        assertThat(registry.get("scim.archive.drift").tag("kind", "missing").gauge().value()).isZero();

        // when — 첫 아카이빙에서 3·1 을 보고, 다음 아카이빙에서 0·2 를 본다
        metrics.observed(3L, 1L);
        metrics.observed(0L, 2L);

        // then — 합이 아니라 마지막 값이다
        assertThat(registry.get("scim.archive.drift").tag("kind", "extra").gauge().value()).isEqualTo(0);
        assertThat(registry.get("scim.archive.drift").tag("kind", "missing").gauge().value()).isEqualTo(2);
    }

    @Test
    @DisplayName("관찰 시각 게이지는 아카이빙이 관찰할 때마다 바뀌고, 관찰 전에는 값이 없다 — 다른 인스턴스의 낡은 값과 구별한다(설계 2026-10-20 §6.4)")
    void 관찰_시각_게이지는_관찰할_때마다_바뀌고_관찰_전에는_값이_없다() {
        // given — 이 인스턴스는 아직 아카이빙을 관찰하지 않았다
        // 준비한다() 가 같은 이름의 게이지를 이미 등록한 registry 는 쓰지 않는다 — 처음 등록한 쪽의 값을 읽게 된다
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        움직이는_시계 시계 = new 움직이는_시계(Instant.parse("2026-10-22T03:00:00Z"));
        ScimSyncMetrics 지표 = new ScimSyncMetrics(registry, 시계);
        assertThat(registry.get("scim.archive.drift.observed").gauge().value())
                .as("관찰한 적이 없으면 0 이 아니라 값이 없다 — '관찰했고 0건' 과 구별된다").isNaN();

        // when — 어긋남이 없는 관찰(0, 0)
        지표.observed(0L, 0L);

        // then — 0건도 관찰이다
        assertThat(registry.get("scim.archive.drift.observed").gauge().value())
                .isEqualTo(Instant.parse("2026-10-22T03:00:00Z").getEpochSecond());
        assertThat(registry.get("scim.archive.drift").tag("kind", "extra").gauge().value()).isZero();

        // when — 하루 뒤 다음 관찰
        시계.now = Instant.parse("2026-10-23T03:00:05Z");
        지표.observed(2L, 1L);

        // then
        assertThat(registry.get("scim.archive.drift.observed").gauge().value())
                .isEqualTo(Instant.parse("2026-10-23T03:00:05Z").getEpochSecond());
        assertThat(registry.get("scim.archive.drift").tag("kind", "extra").gauge().value()).isEqualTo(2);
    }

    /** 시각을 직접 옮기는 시계 — 관찰 시각 게이지가 관찰할 때마다 읽는지 본다. */
    private static class 움직이는_시계 extends Clock {

        Instant now;

        움직이는_시계(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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

    @Test
    @DisplayName("받아서 버린 속성은 이름 태그를 달고 하나씩 오른다(설계 2026-10-06 §3.3)")
    void 버린_속성을_센다() {
        // when
        metrics.ignored(Set.of("phoneNumbers", "other"));
        metrics.ignored(Set.of("phoneNumbers"));

        // then
        assertThat(registry.counter("scim.patch.ignored", "attribute", "phoneNumbers").count()).isEqualTo(2);
        assertThat(registry.counter("scim.patch.ignored", "attribute", "other").count()).isEqualTo(1);
    }
}

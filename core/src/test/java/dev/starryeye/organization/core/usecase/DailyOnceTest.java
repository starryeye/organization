package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 한 번 작업은 클러스터 전체에서 한 번만 (설계 2026-09-30 §5.1).
 */
class DailyOnceTest {

    private static final Instant 오늘_새벽 = Instant.parse("2026-09-30T18:00:00Z");

    @Test
    @DisplayName("같은 날 두 인스턴스가 부르면 표지를 먼저 잡은 쪽만 돈다")
    void 같은_날에는_한_번만_돈다() {
        // given — 표지 저장소를 함께 쓰는 두 인스턴스
        var claims = new FakeDailyJobClaims();
        var 가 = new DailyOnce(claims, Clock.fixed(오늘_새벽, ZoneOffset.UTC));
        var 나 = new DailyOnce(claims, Clock.fixed(오늘_새벽.plusSeconds(1), ZoneOffset.UTC));
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when
        가.run("scim-archive", 작업).block();
        나.run("scim-archive", 작업).block();

        // then
        assertThat(돈_횟수).hasValue(1);
    }

    @Test
    @DisplayName("다음 날이나 다른 작업은 따로 센다")
    void 날짜와_작업마다_따로_센다() {
        // given
        var claims = new FakeDailyJobClaims();
        var 오늘 = new DailyOnce(claims, Clock.fixed(오늘_새벽, ZoneOffset.UTC));
        var 내일 = new DailyOnce(claims, Clock.fixed(오늘_새벽.plusSeconds(86400), ZoneOffset.UTC));
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when
        오늘.run("scim-archive", 작업).block();
        오늘.run("scim-purge", 작업).block();
        내일.run("scim-archive", 작업).block();

        // then
        assertThat(돈_횟수).hasValue(3);
    }
}

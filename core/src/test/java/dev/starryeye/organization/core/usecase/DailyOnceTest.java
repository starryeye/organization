package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 하루 한 번 작업은 클러스터 전체에서 한 번만 (설계 2026-09-30 §5.1). 표지는 성공해야 DONE 이고, 실패·성공 아님·취소면 풀어 같은 날 다시 잡힌다. 잡은 채 죽은
 * 인스턴스의 표지는 임대가 끝나면 다시 잡힌다(설계 2026-10-27 §9).
 */
class DailyOnceTest {

    private static final Instant 오늘_새벽 = Instant.parse("2026-09-30T18:00:00Z");
    private static final LocalDate 오늘 = LocalDate.parse("2026-09-30");
    private static final Duration 기한 = Duration.ofMinutes(30);

    /** 가짜 표지의 임대 판정과 {@link DailyOnce} 의 시계가 함께 보는 지금 — 시험이 손으로 옮긴다. */
    private final AtomicReference<Instant> 지금 = new AtomicReference<>(오늘_새벽);
    private final FakeDailyJobClaims claims = new FakeDailyJobClaims(지금::get);

    /** {@link #지금} 을 읽는 시계. */
    private static final class 옮기는_시계 extends Clock {

        private final AtomicReference<Instant> 지금;

        옮기는_시계(AtomicReference<Instant> 지금) {
            this.지금 = 지금;
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
            return 지금.get();
        }
    }

    /** 표지 저장소를 함께 쓰는 인스턴스 하나. */
    private DailyOnce 인스턴스() {
        return new DailyOnce(claims, new 옮기는_시계(지금), 기한);
    }

    private void 옮긴다(Duration 만큼) {
        지금.set(지금.get().plus(만큼));
    }

    @Test
    @DisplayName("같은 날 두 인스턴스가 부르면 표지를 먼저 잡은 쪽만 돈다")
    void 같은_날에는_한_번만_돈다() {
        // given — 표지 저장소를 함께 쓰는 두 인스턴스
        var 가 = 인스턴스();
        var 나 = 인스턴스();
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when
        가.run("scim-archive", 작업, v -> true).block();
        옮긴다(Duration.ofSeconds(1));
        나.run("scim-archive", 작업, v -> true).block();

        // then
        assertThat(돈_횟수).hasValue(1);
    }

    @Test
    @DisplayName("다음 날이나 다른 작업은 따로 센다")
    void 날짜와_작업마다_따로_센다() {
        // given
        var 인스턴스 = 인스턴스();
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when
        인스턴스.run("scim-archive", 작업, v -> true).block();
        인스턴스.run("scim-purge", 작업, v -> true).block();
        옮긴다(Duration.ofDays(1));
        인스턴스.run("scim-archive", 작업, v -> true).block();

        // then
        assertThat(돈_횟수).hasValue(3);
    }

    @Test
    @DisplayName("일이 실패하면 표지를 풀어 같은 날 다음 시도가 다시 잡아 돈다 — 실패는 그대로 낸다")
    void 실패하면_풀어_같은_날_다시_잡힌다() {
        // given
        var 가 = 인스턴스();
        var 나 = 인스턴스();
        var 실패 = new IllegalStateException("첫 시도 실패");
        AtomicInteger 돈_횟수 = new AtomicInteger();

        // when
        assertThatThrownBy(() -> 가.run("scim-archive", Mono.error(실패), v -> true).block()).isSameAs(실패);
        옮긴다(Duration.ofHours(1));
        나.run("scim-archive", Mono.fromSupplier(돈_횟수::incrementAndGet), v -> true).block();

        // then
        assertThat(돈_횟수).hasValue(1);
        assertThat(claims.상태("scim-archive", 오늘)).contains("DONE");
    }

    @Test
    @DisplayName("값이 성공 판정을 통과하지 못하면 표지를 풀어 다시 잡힌다 — 값은 그대로 낸다")
    void 성공이_아니면_풀어_다시_잡힌다() {
        // given — 아카이빙이 FAILED 를 값으로 돌려준 경우
        var 가 = 인스턴스();
        var 나 = 인스턴스();
        AtomicInteger 돈_횟수 = new AtomicInteger();

        // when
        var 첫_값 = 가.run("scim-archive", Mono.just("FAILED"), 값 -> 값.equals("SUCCEEDED")).block();
        var 첫_상태 = claims.상태("scim-archive", 오늘);
        옮긴다(Duration.ofHours(1));
        나.run("scim-archive", Mono.fromSupplier(돈_횟수::incrementAndGet), v -> true).block();

        // then
        assertThat(첫_값).isEqualTo("FAILED");
        assertThat(첫_상태).isEmpty();
        assertThat(돈_횟수).hasValue(1);
    }

    @Test
    @DisplayName("성공하면 표지는 DONE 이고 같은 날 다음 시도는 건너뛴다")
    void 성공하면_DONE_이고_같은_날은_건너뛴다() {
        // given
        var 가 = 인스턴스();
        var 나 = 인스턴스();
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when
        var 값 = 가.run("ldap-purge", 작업, v -> true).block();
        옮긴다(Duration.ofHours(1));
        var 둘째 = 나.run("ldap-purge", 작업, v -> true).block();

        // then
        assertThat(값).isEqualTo(1);
        assertThat(둘째).isNull();
        assertThat(돈_횟수).hasValue(1);
        assertThat(claims.상태("ldap-purge", 오늘)).contains("DONE");
    }

    @Test
    @DisplayName("잡은 채 멈춘 인스턴스의 CLAIMED 는 임대(잡은 때 + 기한 + 5분)가 끝나야 다시 잡힌다")
    void 임대가_끝난_CLAIMED_는_다시_잡힌다() {
        // given — 가는 잡은 뒤 끝나지 않는다(죽은 인스턴스)
        var 가 = 인스턴스();
        var 나 = 인스턴스();
        Sinks.One<Integer> 가의_일 = Sinks.one();
        가.run("scim-archive", 가의_일.asMono(), v -> true).subscribe();
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when — 34분 뒤는 아직 임대 안이고, 36분 뒤는 임대가 끝났다
        옮긴다(Duration.ofMinutes(34));
        나.run("scim-archive", 작업, v -> true).block();
        var 임대_안에서_돈_횟수 = 돈_횟수.get();
        옮긴다(Duration.ofMinutes(2));
        나.run("scim-archive", 작업, v -> true).block();

        // then
        assertThat(임대_안에서_돈_횟수).isZero();
        assertThat(돈_횟수).hasValue(1);
        assertThat(claims.상태("scim-archive", 오늘)).contains("DONE");
    }

    @Test
    @DisplayName("임대를 잃은 인스턴스가 늦게 실패해도 새로 잡은 쪽의 표지는 풀지 않는다")
    void 남의_표지는_풀지_않는다() {
        // given — 가가 잡은 채 매달리고, 임대가 끝난 뒤 나가 잡아 역시 돌고 있다
        var 가 = 인스턴스();
        var 나 = 인스턴스();
        Sinks.One<String> 가의_일 = Sinks.one();
        Sinks.One<String> 나의_일 = Sinks.one();
        AtomicReference<Throwable> 가의_오류 = new AtomicReference<>();
        가.run("scim-archive", 가의_일.asMono(), v -> true).subscribe(v -> {
        }, 가의_오류::set);
        옮긴다(Duration.ofMinutes(36));
        나.run("scim-archive", 나의_일.asMono(), v -> true).subscribe();
        var 늦은_실패 = new IllegalStateException("가의 늦은 실패");

        // when
        가의_일.tryEmitError(늦은_실패);
        var 가가_끝난_뒤 = claims.상태("scim-archive", 오늘);
        나의_일.tryEmitValue("SUCCEEDED");

        // then
        assertThat(가의_오류).hasValue(늦은_실패);
        assertThat(가가_끝난_뒤).as("나의 CLAIMED 가 남는다").contains("CLAIMED");
        assertThat(claims.상태("scim-archive", 오늘)).contains("DONE");
    }

    @Test
    @DisplayName("일이 결과 없이 끝나면 표지를 풀어 다시 잡힌다")
    void 빈_끝이면_풀어_다시_잡힌다() {
        // given
        var 가 = 인스턴스();

        // when
        var 값 = 가.run("scim-purge", Mono.<Integer>empty(), v -> true).block();

        // then
        assertThat(값).isNull();
        assertThat(claims.상태("scim-purge", 오늘)).isEmpty();
    }

    @Test
    @DisplayName("돌던 일이 취소되면 표지를 풀어 다시 잡힌다")
    void 취소되면_풀어_다시_잡힌다() {
        // given
        var 가 = 인스턴스();
        Sinks.One<Integer> 가의_일 = Sinks.one();
        Disposable 구독 = 가.run("scim-archive", 가의_일.asMono(), v -> true).subscribe();
        var 돌던_중 = claims.상태("scim-archive", 오늘);

        // when
        구독.dispose();

        // then
        assertThat(돌던_중).contains("CLAIMED");
        assertThat(claims.상태("scim-archive", 오늘)).isEmpty();
    }

    @Test
    @DisplayName("자정을 넘겨 끝나도 잡은 날의 표지를 끝낸다")
    void 자정을_넘겨도_잡은_날의_표지를_끝낸다() {
        // given — 자정 1분 전에 잡는다
        지금.set(Instant.parse("2026-09-30T23:59:00Z"));
        var 가 = 인스턴스();
        Sinks.One<Integer> 가의_일 = Sinks.one();
        가.run("scim-archive", 가의_일.asMono(), v -> true).subscribe();

        // when
        옮긴다(Duration.ofMinutes(2));
        가의_일.tryEmitValue(1);

        // then
        assertThat(claims.상태("scim-archive", 오늘)).contains("DONE");
        assertThat(claims.상태("scim-archive", 오늘.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("표지를 완료로 적지 못해도 일의 값은 그대로 낸다 — 끝내기의 오류는 삼킨다")
    void 끝내기의_오류는_삼킨다() {
        // given — 완료 쓰기가 실패하는 표지 저장소
        var 실패하는_표지 = new FakeDailyJobClaims(지금::get) {
            @Override
            public Mono<Void> complete(String job, LocalDate day, String owner) {
                return Mono.error(new IllegalStateException("완료 쓰기 실패"));
            }
        };
        var 가 = new DailyOnce(실패하는_표지, new 옮기는_시계(지금), 기한);

        // when
        var 값 = 가.run("ldap-purge", Mono.just(7L), v -> true).block();

        // then
        assertThat(값).isEqualTo(7L);
        assertThat(실패하는_표지.상태("ldap-purge", 오늘)).contains("CLAIMED");
    }
}

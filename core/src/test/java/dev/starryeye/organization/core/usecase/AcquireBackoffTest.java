package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 다른 서버가 쥔 락을 다시 시도하는 간격과 한도(설계 2026-10-07 §3.2·§3.3), 시도마다의 한도와 끊긴 시도의 다시 시도(설계 2026-10-27 §6). */
class AcquireBackoffTest {

    /** 늘 상한만큼 기다린다 — 대기 = min(100ms, 10ms × 2ⁿ). */
    private static final AcquireBackoff 상한만큼 = new AcquireBackoff(() -> 1.0);

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    private static Mono<String> 늘_실패하는_시도(AtomicInteger 시도, Supplier<Throwable> 실패) {
        return Mono.defer(() -> {
            시도.incrementAndGet();
            return Mono.error(실패.get());
        });
    }

    @Test
    @DisplayName("n번째 재시도 대기는 0 과 min(100ms, 10ms × 2ⁿ) 사이이고 무작위 값에 비례한다 — full jitter")
    void 대기는_상한_안이다() {
        // when, then
        assertThat(상한만큼.대기(0)).isEqualTo(Duration.ofMillis(10));
        assertThat(상한만큼.대기(1)).isEqualTo(Duration.ofMillis(20));
        assertThat(상한만큼.대기(3)).isEqualTo(Duration.ofMillis(80));
        assertThat(상한만큼.대기(4)).as("100ms 에서 멈춘다").isEqualTo(Duration.ofMillis(100));
        assertThat(상한만큼.대기(62)).as("자리 넘침 없이 100ms").isEqualTo(Duration.ofMillis(100));
        assertThat(new AcquireBackoff(() -> 0.5).대기(2)).isEqualTo(Duration.ofMillis(20));
        assertThat(new AcquireBackoff(() -> 0.0).대기(5)).isZero();
    }

    @Test
    @DisplayName("다른 SCIM 쓰기가 쥐고 있으면 한도까지 다시 시도하고, 다 쓰면 마지막 실패의 대기(2초)를 물려받는다")
    void 한도까지_다시_시도한다() {
        // given — 0·10·30·70·150·250ms 에 시도한다. 다음 대기 100ms 를 더하면 350ms 라 한도 300ms 를 넘는다
        AtomicInteger 시도 = new AtomicInteger();

        // when, then
        StepVerifier.withVirtualTime(() -> 상한만큼.잡는다(한도 ->
                        늘_실패하는_시도(시도, () -> LockUnavailableException.잡혀_있다(LockPurpose.WRITE)), Duration.ofMillis(300)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(249))
                .thenAwait(Duration.ofMillis(1))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(LockUnavailableException.class, 실패 -> {
                    assertThat(실패).hasMessageContaining("변경 락을 얻지 못했습니다");
                    assertThat(실패.retryAfter()).isEqualTo(Duration.ofSeconds(2));
                }))
                .verify();
        assertThat(시도).hasValue(6);
    }

    @ParameterizedTest
    @EnumSource(value = LockPurpose.class, names = {"REBUILD", "SYNC"})
    @DisplayName("재적재·동기화가 쥐고 있으면 다시 시도하지 않고 그 실패(60초)를 그대로 낸다")
    void 긴_작업이면_바로_끝낸다(LockPurpose 용도) {
        // given
        AtomicInteger 시도 = new AtomicInteger();

        // when
        Throwable 실패 = catchThrowable(() -> 상한만큼.잡는다(한도 ->
                늘_실패하는_시도(시도, () -> LockUnavailableException.잡혀_있다(용도)), Duration.ofSeconds(3)).block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("쥔 쪽 용도를 모르면 쓰기 경합처럼 다시 시도한다")
    void 용도를_모르면_다시_시도한다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        Mono<String> 두_번_뒤_성공 = Mono.defer(() -> 시도.incrementAndGet() <= 2
                ? Mono.error(LockUnavailableException.잡혀_있다(null))
                : Mono.just("잡았다"));

        // when, then
        StepVerifier.withVirtualTime(() -> 상한만큼.잡는다(한도 -> 두_번_뒤_성공, Duration.ofSeconds(3)))
                .thenAwait(Duration.ofMillis(100))
                .expectNext("잡았다")
                .verifyComplete();
        assertThat(시도).hasValue(3);
    }

    @Test
    @DisplayName("락 이외의 오류는 다시 시도하지 않고 그대로 흘린다 — 503 으로 옮기는 것은 유스케이스다")
    void 락_이외의_오류는_그대로다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        RuntimeException 저장소장애 = new RuntimeException("DynamoDB 가 응답하지 않는다(테스트)");

        // when
        Throwable 실패 = catchThrowable(() -> 상한만큼.잡는다(한도 -> 늘_실패하는_시도(시도, () -> 저장소장애), Duration.ofSeconds(3)).block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isSameAs(저장소장애);
    }

    @Test
    @DisplayName("한도가 0 이면 한 번만 시도하고 마지막 실패의 대기를 물려받는다")
    void 한도_0이면_한_번이다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();

        // when
        Throwable 실패 = catchThrowable(() -> 상한만큼.잡는다(한도 ->
                늘_실패하는_시도(시도, () -> LockUnavailableException.잡혀_있다(LockPurpose.WRITE)), Duration.ZERO).block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("백오프는 날아가는 중인 시도를 스스로 끊지 않는다 — 끊는 것은 시도 한도를 받은 저장소이고, 그 끊김은 결과를 모르는 실패로 온다")
    void 날아가는_시도를_끊지_않는다() {
        // given — 시도 하나가 받은 한도를 무시하고 500ms 걸린다. 한도는 300ms 다. 끝난 뒤 오는 cancel 은 끊은 것이 아니다 — retryWhen 이 끝난 시도를 정리하며 보낸다
        AtomicInteger 시도 = new AtomicInteger();
        AtomicBoolean 끝났다 = new AtomicBoolean();
        AtomicBoolean 끊겼다 = new AtomicBoolean();

        // when, then — 300ms 에 끊지 않고 500ms 에 시도가 끝난 뒤 멈춘다
        StepVerifier.withVirtualTime(() -> 상한만큼.잡는다(한도 -> Mono.defer(() -> {
                            시도.incrementAndGet();
                            return Mono.delay(Duration.ofMillis(500))
                                    .then(Mono.<String>error(LockUnavailableException.잡혀_있다(LockPurpose.WRITE)))
                                    .doOnError(e -> 끝났다.set(true))
                                    .doOnCancel(() -> {
                                        if (!끝났다.get()) {
                                            끊겼다.set(true);
                                        }
                                    });
                        }), Duration.ofMillis(300)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(499))
                .thenAwait(Duration.ofMillis(1))
                .expectError(LockUnavailableException.class)
                .verify();
        assertThat(시도).hasValue(1);
        assertThat(끊겼다).isFalse();
    }

    @Test
    @DisplayName("시도마다 남은 한도와 1초 중 작은 값을 시도 한도로 주고, 200ms 아래로는 내리지 않는다 — 끊긴 시도가 한도에 닿으면 기본 대기(10초)의 503 이다")
    void 시도마다_남은_한도와_1초_중_작은_값을_주고_200ms_아래로_내리지_않는다() {
        // given — 한도 2.1초. 시도는 받은 한도를 다 쓰고 끊긴다: 0초에 1초, 1초에 1초(남은 1.1초), 2초에 200ms(남은 0.1초) → 2.2초에 한도를 넘겼다
        List<Duration> 받은_한도 = new CopyOnWriteArrayList<>();

        // when, then
        StepVerifier.withVirtualTime(() -> 상한만큼.<String>잡는다(한도 -> {
                            받은_한도.add(한도);
                            return Mono.delay(한도).then(Mono.error(new LockAttemptTimeoutException("시도 한도로 끊겼다(테스트)")));
                        }, Duration.ofMillis(2100)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(2199))
                .thenAwait(Duration.ofMillis(1))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(LockUnavailableException.class, 실패 -> {
                    assertThat(실패).hasMessageContaining("변경 락을 얻지 못했습니다");
                    assertThat(실패.retryAfter()).isEqualTo(Duration.ofSeconds(10));
                }))
                .verify(Duration.ofSeconds(5));
        assertThat(받은_한도).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(200));
    }

    @Test
    @DisplayName("시도 한도로 끊긴 시도는 기다리지 않고 곧바로 다시 시도한다")
    void 끊긴_시도는_기다리지_않고_다시_시도한다() {
        // given — 첫 시도는 1초에 끊기고 둘째는 바로 잡는다
        AtomicInteger 시도 = new AtomicInteger();

        // when, then — 백오프 대기 없이 정확히 1초에 값이 온다
        StepVerifier.withVirtualTime(() -> 상한만큼.잡는다(한도 -> Mono.defer(() -> 시도.incrementAndGet() == 1
                        ? Mono.delay(한도).then(Mono.<String>error(new LockAttemptTimeoutException("시도 한도로 끊겼다(테스트)")))
                        : Mono.just("잡았다")), Duration.ofSeconds(3)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(999))
                .thenAwait(Duration.ofMillis(1))
                .expectNext("잡았다")
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(시도).hasValue(2);
    }
}

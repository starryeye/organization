package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스를 쥔 채 일을 돌린다 (설계 2026-10-02 §3.1).
 */
class LeaseKeeperTest {

    private FakeMutationLock lock;
    private final List<String> 상실 = new CopyOnWriteArrayList<>();
    private LockObserver observer;

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        observer = new LockObserver() {
            @Override
            public void acquireFinished(Duration waited, boolean contended) {
            }

            @Override
            public void leaseLost(String reason) {
                상실.add(reason);
            }
        };
    }

    @Test
    @DisplayName("일이 갱신 주기보다 오래 걸리면 주기마다 리스를 갱신하고, 일이 끝나면 갱신도 멈춘다")
    void 주기마다_갱신하고_끝나면_멈춘다() throws InterruptedException {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofMillis(50), observer);

        // when
        String 결과 = keeper.keep(lease, Mono.delay(Duration.ofMillis(300)).thenReturn("끝"), "테스트").block();

        // then
        assertThat(결과).isEqualTo("끝");
        assertThat(lock.renewed.get()).isGreaterThanOrEqualTo(3);
        int 끝났을때 = lock.renewAttempted.get();
        Thread.sleep(200);
        assertThat(lock.renewAttempted.get()).as("일이 끝난 뒤에는 갱신하지 않는다").isEqualTo(끝났을때);
        assertThat(상실).isEmpty();
    }

    @Test
    @DisplayName("갱신이 실패하면 하던 일을 취소하고 '리스를 잃었다'로 끝난다")
    void 갱신이_실패하면_멈춘다() {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.failRenew = true;
        AtomicBoolean 취소됨 = new AtomicBoolean();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofMillis(50), observer);

        // when, then
        assertThatThrownBy(() -> keeper.keep(lease, Mono.<String>never().doOnCancel(() -> 취소됨.set(true)),
                        "테스트 도중 리스 상실").block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class)
                .hasMessageContaining("리스");
        assertThat(취소됨).isTrue();
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
    }

    @Test
    @DisplayName("갱신이 답하지 않으면 — 반쯤 열린 연결 등 — 한 번 시도는 주기의 절반에서 끊고 다시 시도하되, 주기 두 번 안에 멈춘다")
    void 갱신이_응답하지_않으면_주기_안에_멈춘다() {
        // given
        FakeMutationLock 답없는_락 = new FakeMutationLock() {
            @Override
            public Mono<LockLease> renew(LockLease lease) {
                renewAttempted.incrementAndGet();
                return Mono.never();
            }
        };
        LockLease lease = 답없는_락.acquire(MutationLock.LockPurpose.WRITE).block();
        AtomicBoolean 취소됨 = new AtomicBoolean();
        LeaseKeeper keeper = new LeaseKeeper(답없는_락, Duration.ofSeconds(10), observer);

        // when, then — 첫 갱신은 10초에 나가 15초에 끊기고, 15.2초의 두 번째 시도도 답하지 않아 20초(시작 + 2×주기)에 잃은 것으로 본다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.<String>never().doOnCancel(() -> 취소됨.set(true)),
                        "테스트 도중 리스 상실"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(20))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(LockUnavailableException.class)
                        .hasMessage("작업 도중 락 리스를 잃었습니다")
                        .hasCauseInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(5));
        assertThat(취소됨).isTrue();
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
        assertThat(답없는_락.renewAttempted.get()).as("한 번 시도는 주기의 절반에서 끊고 다시 시도한다").isEqualTo(2);
    }

    @Test
    @DisplayName("빈 결과로 끝나는 일은 그대로 빈 결과다")
    void 빈_결과는_그대로다() {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then
        assertThat(keeper.keep(lease, Mono.<String>empty(), "테스트").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("갱신이 일시 오류로 실패하면 쉬며 다시 시도하고, 성공하면 일은 계속된다")
    void 일시_오류는_다시_시도한다() {
        // given — 10초의 첫 갱신이 두 번 실패한 뒤 성공한다(10초, 10.2초 실패, 10.6초 성공). 20초 갱신은 곧바로 성공한다
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.transientRenewFailures.set(2);
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then — 25초 걸리는 일이 끝까지 간다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.delay(Duration.ofSeconds(25)).thenReturn("끝"), "테스트"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(25))
                .expectNext("끝")
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(상실).isEmpty();
        assertThat(lock.renewAttempted.get()).isEqualTo(4);
        assertThat(lock.renewed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("일시 오류가 이어져 마지막 성공에서 갱신 주기 두 번이 지나면 멈춘다 — 그 전에는 멈추지 않는다")
    void 일시_오류가_한도를_넘으면_멈춘다() {
        // given — 갱신이 늘 일시 오류다
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.transientRenewFailures.set(Integer.MAX_VALUE);
        AtomicBoolean 취소됨 = new AtomicBoolean();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then — 19초까지는 일이 돈다. 20초(시작 + 2×주기)에 멈춘다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.<String>never().doOnCancel(() -> 취소됨.set(true)),
                        "테스트 도중 리스 상실"))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(19))
                .thenAwait(Duration.ofSeconds(1))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(LockUnavailableException.class)
                        .hasCauseInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(5));
        assertThat(취소됨).isTrue();
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
        assertThat(lock.renewAttempted.get()).as("한도 안에서 여러 번 시도했다").isGreaterThan(5);
    }

    @Test
    @DisplayName("갱신이 조건 실패(남이 가져감)로 끝나면 다시 시도하지 않고 곧바로 멈춘다")
    void 조건_실패는_다시_시도하지_않는다() {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.failRenew = true;
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then — 10초의 첫 갱신에서 곧바로 끝난다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.<String>never(), "테스트 도중 리스 상실"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(10))
                .expectError(LockUnavailableException.class)
                .verify(Duration.ofSeconds(5));
        assertThat(lock.renewAttempted.get()).isEqualTo(1);
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
    }
}

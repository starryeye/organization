package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 획득 재시도를 다 쓰고 나서 새로 만드는 예외는 마지막 실패의 기다릴 시간을 물려받는다(설계 2026-10-05 §3.1).
 * 쥔 쪽이 재적재면 IdP 가 2초 뒤에 다시 와도 또 막히므로, 응답의 {@code Retry-After} 가 쥔 쪽의 용도를 따라가야 한다.
 */
class IncrementalSyncLockRetryAfterTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        state.users.put("kim", new DirectoryUser("kim", "emp-kim", "kim", "kim", "kim@example.com", true));
    }

    /** 시도 번호(0부터)별로 실패를 정하는 락. 한 번도 잡히지 않는다. */
    private static MutationLock 잡히지_않는_락(AtomicInteger 시도, IntFunction<LockUnavailableException> 실패) {
        return new MutationLock() {
            @Override
            public Mono<LockLease> acquire(LockPurpose purpose) {
                return Mono.defer(() -> Mono.error(실패.apply(시도.getAndIncrement())));
            }

            @Override
            public Mono<Void> release(LockLease lease) {
                return Mono.empty();
            }

            @Override
            public Mono<LockLease> renew(LockLease lease) {
                return Mono.just(lease);
            }

            @Override
            public Mono<LockPurpose> peek() {
                return Mono.empty();
            }
        };
    }

    /** 200ms 간격으로 두 번 재시도할 수 있는 예산이다(처음을 합쳐 세 번 시도한다). */
    private IncrementalSyncUseCase 유스케이스(MutationLock lock) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ofMillis(400),
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    @Test
    @DisplayName("재적재가 쥔 락을 끝내 못 잡으면 기다릴 시간이 60초다")
    void 재시도를_다_쓰면_쥔_쪽의_기다릴_시간을_물려받는다() {
        // given
        var 시도 = new AtomicInteger();
        var 재적재가_쥔_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.REBUILD));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(재적재가_쥔_락).removeUser("kim").block());

        // then
        assertThat(시도).as("처음 한 번과 재시도 두 번").hasValue(3);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("쥔 쪽이 도중에 바뀌면 가장 긴 시간이 아니라 마지막 실패의 기다릴 시간을 따른다 — 재적재가 끝나고 쓰기가 쥐면 2초")
    void 마지막_실패의_시간을_따른다() {
        // given — 처음엔 재적재가 쥐고 있다가 끝나고 SCIM 쓰기가 쥐었다. 가장 긴 시간(60초)과 마지막 시간(2초)이 갈린다
        var 시도 = new AtomicInteger();
        var 쥔_쪽이_바뀌는_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(
                n == 0 ? MutationLock.LockPurpose.REBUILD : MutationLock.LockPurpose.WRITE));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(쥔_쪽이_바뀌는_락).removeUser("kim").block());

        // then
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
    }
}

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
 * 획득을 포기할 때의 기다릴 시간은 쥔 쪽의 용도를 따른다(설계 2026-10-05 §3.1, 2026-10-07 §3.3). 재적재·동기화면 다시 시도하지 않고 바로 60초,
 * 쓰기 경합이면 한도까지 다시 시도한 뒤 마지막 실패의 2초다.
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

    /** 한도 400ms — 쓰기 경합이면 백오프로 여러 번 다시 시도할 수 있다. */
    private IncrementalSyncUseCase 유스케이스(MutationLock lock) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ofMillis(400),
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    @Test
    @DisplayName("재적재가 쥔 락이면 다시 시도하지 않고 바로 60초다 — 몇 분 걸리는 일이라 한도 안의 재시도가 결과를 바꾸지 못한다(설계 2026-10-07 §3.3)")
    void 재적재가_쥐면_바로_60초다() {
        // given
        var 시도 = new AtomicInteger();
        var 재적재가_쥔_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.REBUILD));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(재적재가_쥔_락).removeUser("kim").block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("쓰기가 쥐어 다시 시도하다 재적재가 쥐면 그 자리에서 멈추고 60초다")
    void 도중에_재적재가_쥐면_멈춘다() {
        // given — 처음엔 SCIM 쓰기가 쥐고 있다가 재적재가 잡았다
        var 시도 = new AtomicInteger();
        var 쥔_쪽이_바뀌는_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(
                n == 0 ? MutationLock.LockPurpose.WRITE : MutationLock.LockPurpose.REBUILD));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(쥔_쪽이_바뀌는_락).removeUser("kim").block());

        // then
        assertThat(시도).hasValue(2);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("쓰기가 끝내 쥐고 있으면 한도까지 다시 시도하고, 마지막 실패의 기다릴 시간(2초)을 물려받는다")
    void 쓰기가_쥐면_한도까지_시도한다() {
        // given
        var 시도 = new AtomicInteger();
        var 쓰기가_쥔_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.WRITE));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(쓰기가_쥔_락).removeUser("kim").block());

        // then
        assertThat(시도.get()).as("백오프로 여러 번 다시 시도했다").isGreaterThan(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
    }
}

package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 서버 안의 SCIM 쓰기 줄(설계 2026-10-07 §3.1). 온 순서대로 차례를 준다 — 차례를 받은 요청 하나만 전역 락(DynamoDB)을 시도하고, 그 요청이
 * 끝나면(반납까지) 바로 다음 요청에 차례가 간다. 같은 서버 안에서는 반납과 다음 획득 사이에 빈 시간이 없고, DynamoDB 락 항목을 두드리는 것은
 * 서버마다 많아야 하나다.
 *
 * <p>줄에서 기다리는 동안에는 아무것도 부르지 않으므로 한도가 지나 빠져도 새는 것이 없다. 차례를 주는 순간과 빠지는 순간이 겹쳐도 차례를
 * 잃지 않고 쥔 쪽이 둘이 되지도 않는다 — 시간 초과와 겹치면 받은 차례를 쓰고, 취소와 겹치면 다음 요청에 넘기고 그 요청에는 내지 않는다.
 */
public final class LocalWriteQueue {

    private static final int WAITING = 0;
    private static final int GRANTED = 1;
    private static final int TAKEN = 2;
    private static final int ABANDONED = 3;

    private final Object 잠금 = new Object();
    private final Deque<Waiter> 대기열 = new ArrayDeque<>();
    /** 지금 누군가 차례를 쥐고 있는가. {@link #잠금} 안에서만 읽고 쓴다. */
    private boolean 차례가_나가_있다;

    /**
     * 줄을 선다. 차례가 오면 {@link Turn} 을 내고, {@code 한도} 안에 오지 않으면 줄에서 빠져 {@code 잡혀_있다(WRITE)}(503, 2초)다 —
     * 앞에 선 것이 이 서버의 SCIM 쓰기다. 줄이 비어 바로 받는 차례도 기다리는 요청과 같은 상태(GRANTED)로 낸다 — 건네받기 전에 취소해도 차례가 다음
     * 요청에 넘어간다.
     *
     * <p>받는 쪽의 계약: {@link Turn} 은 늦게 온 {@code onNext} 를 버리지 않는 연산자({@code flatMap})로 받고, 일이 끝나든 실패하든 취소되든
     * {@code doFinally(넘긴다)} 로 넘긴다. 건네받은 뒤의 취소는 줄이 무시하므로(TAKEN) 받은 쪽이 넘기지 않으면 줄이 멈춘다.
     */
    public Mono<Turn> 줄을_선다(Duration 한도) {
        return Mono.defer(() -> {
            Waiter 나 = new Waiter();
            boolean 바로_받는다;
            synchronized (잠금) {
                바로_받는다 = !차례가_나가_있다;
                if (바로_받는다) {
                    차례가_나가_있다 = true;
                } else {
                    대기열.addLast(나);
                }
            }
            if (바로_받는다) {
                나.차례 = new Turn(false);
                나.상태.set(GRANTED);
                나.신호.tryEmitValue(나.차례);
            }
            return 나.신호.asMono()
                    .timeout(한도, Mono.defer(() -> 시간이_지났다(나)))
                    .filter(차례 -> 나.상태.compareAndSet(GRANTED, TAKEN))
                    .doOnCancel(() -> 떠났다(나));
        });
    }

    private Mono<Turn> 시간이_지났다(Waiter 나) {
        if (나.상태.compareAndSet(WAITING, ABANDONED)) {
            빼낸다(나);
            return Mono.error(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.WRITE));
        }
        // 차례를 주는 순간과 겹쳤다 — 받은 차례를 쓴다. 취소가 먼저 이겼으면(ABANDONED) 차례는 이미 다음 요청에 넘어갔으니 내지 않는다
        return 나.상태.get() == GRANTED ? Mono.just(나.차례) : Mono.empty();
    }

    private void 떠났다(Waiter 나) {
        if (나.상태.compareAndSet(WAITING, ABANDONED)) {
            빼낸다(나);
        } else if (나.상태.compareAndSet(GRANTED, ABANDONED)) {
            // 차례를 받았지만 건네받기 전에 떠났다 — 쥔 채로 사라지면 줄이 멈춘다
            나.차례.넘긴다();
        }
    }

    private void 빼낸다(Waiter 나) {
        synchronized (잠금) {
            대기열.remove(나);
        }
    }

    /** 다음 요청에 차례를 준다. 이미 떠난 요청은 건너뛴다. 줄이 비었으면 차례를 거둔다. */
    private void 다음에게() {
        while (true) {
            Waiter 다음;
            synchronized (잠금) {
                다음 = 대기열.pollFirst();
                if (다음 == null) {
                    차례가_나가_있다 = false;
                    return;
                }
            }
            다음.차례 = new Turn(true);
            if (다음.상태.compareAndSet(WAITING, GRANTED)) {
                다음.신호.tryEmitValue(다음.차례);
                return;
            }
        }
    }

    /** 받은 차례. 일이 끝나면(반납까지, 또는 실패 뒤) {@link #넘긴다} 를 부른다 — 여러 번 불러도 한 번만 넘긴다. */
    public final class Turn {

        private final boolean 밀렸다;
        private final AtomicBoolean 넘겼다 = new AtomicBoolean();

        private Turn(boolean 밀렸다) {
            this.밀렸다 = 밀렸다;
        }

        /** 앞에 다른 요청이 있어 줄에서 기다렸는가 — {@code scim.lock.contended} 에 든다. */
        public boolean 밀렸다() {
            return 밀렸다;
        }

        public void 넘긴다() {
            if (넘겼다.compareAndSet(false, true)) {
                다음에게();
            }
        }
    }

    private static final class Waiter {
        final Sinks.One<Turn> 신호 = Sinks.one();
        final AtomicInteger 상태 = new AtomicInteger(WAITING);
        volatile Turn 차례;
    }
}

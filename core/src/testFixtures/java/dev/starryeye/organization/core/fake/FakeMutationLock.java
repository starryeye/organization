package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 프로세스 안에서만 도는 락. 유스케이스가 락을 <b>제대로 잡고 제대로 반납하는지</b> 를
 * 보는 데 쓴다. 분산 동작 자체는 {@code DynamoDbMutationLockTest} 가 본다.
 */
public class FakeMutationLock implements MutationLock {

    public final AtomicInteger acquired = new AtomicInteger();
    public final AtomicInteger released = new AtomicInteger();
    public final AtomicInteger renewed = new AtomicInteger();

    /** 켜면 획득이 항상 실패한다 — 503 경로를 재현하는 데 쓴다. */
    public boolean failAcquire = false;

    /** 켜면 갱신이 항상 실패한다 — 리스를 잃은 상황을 재현하는 데 쓴다. */
    public boolean failRenew = false;

    private final AtomicReference<String> heldToken = new AtomicReference<>();

    @Override
    public Mono<LockLease> acquire(LockPurpose purpose) {
        return Mono.defer(() -> {
            if (failAcquire) {
                return Mono.error(new LockUnavailableException("락 획득 실패(테스트)"));
            }
            String token = UUID.randomUUID().toString();
            if (!heldToken.compareAndSet(null, token)) {
                return Mono.error(new LockUnavailableException("이미 다른 쪽이 쥐고 있다(테스트)"));
            }
            acquired.incrementAndGet();
            return Mono.just(new LockLease(token, Instant.now().plusSeconds(30)));
        });
    }

    @Override
    public Mono<Void> release(LockLease lease) {
        return Mono.fromRunnable(() -> {
            if (heldToken.compareAndSet(lease.token(), null)) {
                released.incrementAndGet();
            }
        });
    }

    @Override
    public Mono<LockLease> renew(LockLease lease) {
        return Mono.defer(() -> {
            if (failRenew) {
                return Mono.error(new LockUnavailableException("리스를 잃었다(테스트)"));
            }
            if (!lease.token().equals(heldToken.get())) {
                return Mono.error(new LockUnavailableException("리스를 잃었다(테스트)"));
            }
            renewed.incrementAndGet();
            return Mono.just(new LockLease(lease.token(), Instant.now().plusSeconds(30)));
        });
    }
}

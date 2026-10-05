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

    /**
     * {@link #renew} 가 <b>불린 횟수</b> — 토큰 검사보다 먼저 늘어난다. {@code renewed} 는
     * 성공한 갱신만 세므로, 반납된 리스를 계속 갱신하려 드는 새는 하트비트를 {@code renewed}
     * 만으로는 잡지 못한다(매번 실패해 값이 그대로다). 시도 자체가 멈췄는지 보려면 이 값을 쓴다.
     */
    public final AtomicInteger renewAttempted = new AtomicInteger();

    /** 켜면 획득이 항상 실패한다 — 락 저장소 오류로 503(10초)이 되는 경로를 재현하는 데 쓴다. 다른 쪽이 쥔 경합은 실제로 먼저 잡아 재현한다. */
    public boolean failAcquire = false;

    /** 켜면 갱신이 항상 실패한다 — 리스를 잃은 상황을 재현하는 데 쓴다. */
    public boolean failRenew = false;

    /**
     * 켜면 반납이 항상 실패한다 — 스로틀·네트워크 오류로 리스가 만료될 때까지 묶이는
     * 상황을 재현하는 데 쓴다. 이 실패는 응답에 나타나지 않으므로 지표로만 보인다.
     */
    public boolean failRelease = false;

    private final AtomicReference<String> heldToken = new AtomicReference<>();
    private final AtomicReference<LockPurpose> heldPurpose = new AtomicReference<>();

    /** 지금 누군가 쥐고 있는가. 판단 읽기가 락 안에서 일어나는지 보는 테스트가 쓴다. */
    public boolean isHeld() {
        return heldToken.get() != null;
    }

    @Override
    public Mono<LockLease> acquire(LockPurpose purpose) {
        return Mono.defer(() -> {
            if (failAcquire) {
                // 락 저장소 오류를 흉내 낸다 — 쥔 쪽이 없으므로 기본 대기(10초)다
                return Mono.error(new LockUnavailableException("락 획득 실패(테스트)"));
            }
            String token = UUID.randomUUID().toString();
            if (!heldToken.compareAndSet(null, token)) {
                // 실제 락과 같다 — 쥔 쪽의 용도로 기다릴 시간이 정해진다(재적재·동기화 60초, 쓰기 2초)
                return Mono.error(LockUnavailableException.잡혀_있다(heldPurpose.get()));
            }
            heldPurpose.set(purpose);
            acquired.incrementAndGet();
            return Mono.just(new LockLease(token, Instant.now().plusSeconds(30)));
        });
    }

    @Override
    public Mono<Void> release(LockLease lease) {
        return Mono.defer(() -> {
            if (failRelease) {
                return Mono.error(new IllegalStateException("반납 실패(테스트)"));
            }
            if (heldToken.compareAndSet(lease.token(), null)) {
                heldPurpose.set(null);
                released.incrementAndGet();
            }
            return Mono.empty();
        });
    }

    @Override
    public Mono<LockLease> renew(LockLease lease) {
        return Mono.defer(() -> {
            renewAttempted.incrementAndGet();
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

    @Override
    public Mono<LockPurpose> peek() {
        return Mono.defer(() -> heldToken.get() == null ? Mono.empty() : Mono.justOrEmpty(heldPurpose.get()));
    }
}

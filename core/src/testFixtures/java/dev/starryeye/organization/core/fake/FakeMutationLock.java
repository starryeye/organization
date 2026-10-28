package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.LockAttemptTimeoutException;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /**
     * 남은 "응답을 잃는" 시도 수 — 0 보다 크면 그 시도는 서버에서 하듯 락을 실제로 잡은 뒤(남이 쥐었으면 못 잡는다), 받은 시도 한도만큼 늦게
     * {@link LockAttemptTimeoutException} 으로 끝나고 하나 준다. 한도가 null 이면 곧바로 끝난다. {@link Integer#MAX_VALUE} 면 늘 끊긴다.
     */
    public final AtomicInteger 응답을_잃는_시도 = new AtomicInteger();

    /** 있으면 획득 시도가 이 오류로 끝난다 — SDK 재시도를 다 쓴 스로틀·5xx·네트워크 오류를 흉내 낸다. 락은 잡지 않는다. */
    public volatile RuntimeException acquireError;

    /** 획득 시도마다(구독할 때) 받은 토큰. */
    public final List<String> 시도한_토큰 = new CopyOnWriteArrayList<>();

    /** 획득 시도마다(구독할 때) 받은 시도 한도. 한도가 없으면 null 이다. */
    public final List<Duration> 시도_한도 = new CopyOnWriteArrayList<>();

    /** 반납이 불릴 때마다(구독할 때) 받은 토큰 — 쥐고 있지 않은 토큰의 반납도 적는다. */
    public final List<String> 반납한_토큰 = new CopyOnWriteArrayList<>();

    /** 켜면 갱신이 항상 실패한다 — 리스를 잃은 상황을 재현하는 데 쓴다. */
    public volatile boolean failRenew = false;

    /**
     * 남은 일시 오류 수 — 0 보다 크면 {@link #renew} 가 스로틀·네트워크 오류를 흉내 낸 {@link IllegalStateException} 으로 끝나고 하나 준다.
     * 리스를 잃은 것(조건 실패, {@link #failRenew})과 달리 다시 시도하면 성공할 수 있는 실패다. {@link Integer#MAX_VALUE} 면 늘 실패한다.
     */
    public final AtomicInteger transientRenewFailures = new AtomicInteger();

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

    /** 같은 토큰이면 이미 쥔 락도 다시 얻는다 — 실제 락의 획득 조건({@code #token = :token})과 같다. */
    @Override
    public Mono<LockLease> acquire(LockPurpose purpose, String token, Duration attemptTimeout) {
        return Mono.defer(() -> {
            시도한_토큰.add(token);
            시도_한도.add(attemptTimeout);
            RuntimeException 오류 = acquireError;
            if (오류 != null) {
                return Mono.error(오류);
            }
            if (failAcquire) {
                // 락 저장소 오류를 흉내 낸다 — 쥔 쪽이 없으므로 기본 대기(10초)다
                return Mono.error(new LockUnavailableException("락 획득 실패(테스트)"));
            }
            boolean 잡았다 = 잡는다(purpose, token);
            if (응답을_잃는_시도.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                // 서버에서는 끝났는데(잡았거나 막혔거나) 응답이 시도 한도 안에 오지 않았다
                Mono<LockLease> 끊김 = Mono.error(new LockAttemptTimeoutException("획득 시도가 한도로 끊겼다(테스트)"));
                return attemptTimeout == null ? 끊김 : Mono.delay(attemptTimeout).then(끊김);
            }
            if (!잡았다) {
                // 실제 락과 같다 — 쥔 쪽의 용도로 기다릴 시간이 정해진다(재적재·동기화 60초, 쓰기 2초)
                return Mono.error(LockUnavailableException.잡혀_있다(heldPurpose.get()));
            }
            return Mono.just(new LockLease(token, Instant.now().plusSeconds(30)));
        });
    }

    /** 비었으면 잡고, 같은 토큰이 쥐고 있으면 그대로 다시 얻는다. 새로 잡을 때만 {@code acquired} 를 센다. */
    private boolean 잡는다(LockPurpose purpose, String token) {
        if (heldToken.compareAndSet(null, token)) {
            heldPurpose.set(purpose);
            acquired.incrementAndGet();
            return true;
        }
        return token.equals(heldToken.get());
    }

    @Override
    public Mono<Void> release(LockLease lease) {
        return Mono.defer(() -> {
            반납한_토큰.add(lease.token());
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
            if (transientRenewFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                return Mono.error(new IllegalStateException("갱신 일시 오류(테스트) — 스로틀 흉내"));
            }
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

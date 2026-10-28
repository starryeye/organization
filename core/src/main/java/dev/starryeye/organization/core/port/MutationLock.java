package dev.starryeye.organization.core.port;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * 인스턴스 전체에서 한 번에 하나만 하게 하는 전역 리스 락 (설계 §4, 2026-09-30 §3).
 *
 * <p>앱마다 테이블이 달라 락도 앱마다 따로다. app-scim 은 SCIM 쓰기와 재적재를, app-ldap 은 동기화와 재적재를 이 락 하나로 줄 세운다.
 *
 * <p><b>왜 전역인가.</b> 엔티티별 락은 "무엇을 잠글지 정하는 것 자체가 읽기" 라는 난점이
 * 있다. 가용성 목적의 배포에서는 동시 쓰기가 드물어 직렬화 비용을 거의 치르지 않으므로
 * 그 복잡도를 사지 않는다 (설계 §4.1).
 *
 * <p><b>리스다.</b> 쥔 쪽이 죽어도 {@code expiresAt} 이 지나면 다른 쪽이 가져간다.
 * 대신 살아있는데 만료될 수 있어 완벽한 상호 배제가 아니다 (설계 §4.7).
 */
public interface MutationLock {

    /**
     * {@code token} 으로 잡는다. 다른 쪽이 쥐고 있으면 {@code LockUnavailableException.잡혀_있다(쥔 용도)} 로 실패한다 — 쥔 용도가 기다릴 시간
     * (재적재·동기화 60초, 쓰기 2초)을 정한다.
     *
     * <p>{@code attemptTimeout} 이 null 이면 클라이언트 전체 한도만 받는다. 있으면 이 시도 하나를 그 안에 끊고, 끊기면
     * {@link dev.starryeye.organization.core.usecase.LockAttemptTimeoutException} 이다 — 서버에서 성공했을 수 있어 결과를 모른다.
     * 같은 토큰의 다시 시도는 이미 성공한 획득을 그대로 다시 얻는다(설계 2026-10-27 §6, 점검 S14). 그래서 끊긴 시도는 같은 토큰으로 다시 시도하고,
     * 포기할 때는 그 토큰으로 반납한다({@link #release} 는 토큰만 본다).
     */
    Mono<LockLease> acquire(LockPurpose purpose, String token, Duration attemptTimeout);

    /** 한 번만 시도하는 쪽(관리 API full·rebuild, 동기화) — 토큰을 새로 만들고 한도는 없다(클라이언트 전체 한도만 받는다). */
    default Mono<LockLease> acquireOnce(LockPurpose purpose) {
        return acquire(purpose, UUID.randomUUID().toString(), null);
    }

    /** 내 토큰일 때만 푼다. 아니면 경고만 남기고 조용히 끝낸다 — 일은 이미 끝났다. */
    Mono<Void> release(LockLease lease);

    /** 만료를 미룬다. 이미 리스를 잃었으면 {@code LockUnavailableException}. */
    Mono<LockLease> renew(LockLease lease);

    /**
     * 지금 쥔 목적을 본다. 아무도 없거나 만료됐으면 빈 Mono. <b>잡지 않는다</b> — 아카이빙처럼 락을 잡으면 안 되는(잡으면 몇 분 동안
     * SCIM 쓰기가 503 이 되는) 쪽이 "재적재 중인가"를 알 때 쓴다(설계 2026-09-30 §5.2).
     */
    Mono<LockPurpose> peek();

    enum LockPurpose {
        WRITE,
        REBUILD,
        /** app-ldap 전체 동기화(정기·수동). 재적재는 {@link #REBUILD}. */
        SYNC
    }
}

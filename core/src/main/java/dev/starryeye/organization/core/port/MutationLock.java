package dev.starryeye.organization.core.port;

import reactor.core.publisher.Mono;

/**
 * SCIM 쓰기와 재적재를 인스턴스 전체에서 직렬화하는 전역 락 (설계 §4).
 *
 * <p><b>왜 전역인가.</b> 엔티티별 락은 "무엇을 잠글지 정하는 것 자체가 읽기" 라는 난점이
 * 있다. 가용성 목적의 배포에서는 동시 쓰기가 드물어 직렬화 비용을 거의 치르지 않으므로
 * 그 복잡도를 사지 않는다 (설계 §4.1).
 *
 * <p><b>리스다.</b> 쥔 쪽이 죽어도 {@code expiresAt} 이 지나면 다른 쪽이 가져간다.
 * 대신 살아있는데 만료될 수 있어 완벽한 상호 배제가 아니다 (설계 §4.7).
 */
public interface MutationLock {

    /** 못 잡으면 {@link dev.starryeye.organization.core.usecase.LockUnavailableException}. */
    Mono<LockLease> acquire(LockPurpose purpose);

    /** 내 토큰일 때만 푼다. 아니면 경고만 남기고 조용히 끝낸다 — 일은 이미 끝났다. */
    Mono<Void> release(LockLease lease);

    /** 만료를 미룬다. 이미 리스를 잃었으면 {@code LockUnavailableException}. */
    Mono<LockLease> renew(LockLease lease);

    enum LockPurpose {
        WRITE,
        REBUILD
    }
}

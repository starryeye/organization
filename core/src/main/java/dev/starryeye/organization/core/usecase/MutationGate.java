package dev.starryeye.organization.core.usecase;

import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 재적재가 도는 동안 변경을 잠시 막는 문. 한 번에 하나만 잡을 수 있어, 재적재끼리 겹치는 것도
 * 같은 장치가 막는다.
 *
 * <p><b>왜 핸들러가 아니라 유스케이스가 확인하나.</b> 확인을 {@link IncrementalSyncUseCase} 의
 * 변경 메서드 안에 두면 <b>모든 변경 경로가 자동으로 덮인다</b>. 핸들러마다 검사를 넣으면
 * 나중에 경로가 하나 늘 때 조용히 빠지고, 그 빠진 곳이 하필 재적재와 경합한다.
 *
 * <p><b>읽기는 막지 않는다.</b> 재적재 중에도 조회 API 와 SCIM 의 GET 은 그대로 동작한다 —
 * 무슨 일이 벌어지는지 들여다보는 것이 오히려 그 순간 가장 필요한 일이다.
 *
 * <p><b>반드시 반납해야 한다.</b> 새면 이후 모든 변경이 영구히 503 이 된다. 호출자는 성공·실패·
 * 취소 어느 경로로 끝나든 반납되도록 {@code doFinally} 로 감싼다.
 *
 * <p>인메모리라 <b>인스턴스 하나 안에서만</b> 유효하다. 여러 대로 늘리면 이 문은 자기 프로세스의
 * 변경만 막고 다른 인스턴스는 그대로 통과시킨다 — 그때는 저장소 수준 장치가 필요하다
 * ({@code docs/superpowers/plans/2026-08-15-follow-ups.md} §6).
 */
public class MutationGate {

    private final AtomicBoolean suspended = new AtomicBoolean(false);

    /** 문을 닫는다. 이미 닫혀 있으면 {@code false} — 재적재가 이미 돌고 있다는 뜻이다. */
    public boolean acquire() {
        return suspended.compareAndSet(false, true);
    }

    public void release() {
        suspended.set(false);
    }

    public boolean isSuspended() {
        return suspended.get();
    }

    /**
     * 열려 있으면 빈 완료, 닫혀 있으면 {@link MutationsSuspendedException}.
     *
     * <p>{@code Mono.defer} 로 감싸 <b>구독 시점</b>에 판단한다. 조립 시점에 읽으면 체인을
     * 만들 때의 상태를 붙들게 되고, 그 사이 재적재가 시작돼도 통과해버린다.
     */
    public Mono<Void> requireOpen() {
        return Mono.defer(() -> suspended.get()
                ? Mono.error(new MutationsSuspendedException("재적재가 진행 중입니다. 잠시 후 다시 시도하세요"))
                : Mono.empty());
    }
}

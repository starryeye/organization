package dev.starryeye.organization.core.usecase;

import java.time.Duration;

/**
 * 분산 락에서 일어난 일을 지표로 남길 수 있게 알린다 (설계 §7).
 *
 * <p>{@code core} 가 Micrometer 를 알지 않게 하려고 콜백으로 받는다 —
 * {@link IncrementalSyncUseCase.DriftObserver} 와 같은 이유, 같은 모양이다. 이 모듈의 의존성은
 * reactor 와 slf4j 뿐이고 그 경계를 지표 때문에 허물지 않는다.
 *
 * <p><b>{@link #leaseLost} 가 특히 중요하다.</b> 리스를 잃는 세 갈래 — 재적재 중 상실, 반납
 * 실패, 획득 도중 취소 — 는 어느 것도 응답에 나타나지 않는다. 지표가 없으면 로그를 사람이
 * 읽을 때까지 아무도 모르고, 그동안 락은 TTL 이 지날 때까지 묶여 있거나 두 인스턴스가 동시에
 * 쓰고 있다.
 */
public interface LockObserver {

    /**
     * 락 획득 시도가 끝났다.
     *
     * @param waited    획득에 성공하거나 포기하기까지 걸린 시간 — {@code scim.lock.wait}
     * @param contended 한 번이라도 다른 쪽에 밀렸는가 — {@code scim.lock.contended}
     */
    void acquireFinished(Duration waited, boolean contended);

    /**
     * 쥐고 있어야 할 리스를 잃었다(또는 잃었을 수 있다) — {@code scim.lock.lease_lost}.
     *
     * @param reason 진단용 문구. 지표 태그로 쓰지 않는다 — 설계 §7 의 표는 태그가 없고,
     *               자유 문자열을 태그로 쓰면 카디널리티가 터진다
     */
    void leaseLost(String reason);

    LockObserver NOOP = new LockObserver() {
        @Override
        public void acquireFinished(Duration waited, boolean contended) {
        }

        @Override
        public void leaseLost(String reason) {
        }
    };
}

package dev.starryeye.organization.ldap.app;

import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 전체 동기화가 겹쳐 도는 것을 막는다.
 * 인스턴스가 하나라는 전제이므로 프로세스 내 플래그로 충분하다.
 */
public class SyncExecutionGuard {

    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    public void release() {
        running.set(false);
    }

    /**
     * 이번 점유를 푸는 반납 수단. 몇 번 불려도 한 번만 푼다 — 작업 쪽(SyncJobs)과 요청 쪽(걸기 실패 처리)이 둘 다 불러도
     * 그사이 남이 잡은 점유를 풀지 않는다.
     */
    public Supplier<Mono<Void>> releaseOnce() {
        AtomicBoolean 반납됨 = new AtomicBoolean(false);
        return () -> Mono.fromRunnable(() -> {
            if (반납됨.compareAndSet(false, true)) {
                release();
            }
        });
    }
}

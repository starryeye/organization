package dev.starryeye.organization.ldap.app;

import java.util.concurrent.atomic.AtomicBoolean;

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
}

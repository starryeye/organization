package dev.starryeye.organization.core.usecase;

/**
 * 락을 잡지 못했거나 쥐고 있던 리스를 잃었다. 호출자는 503 으로 옮긴다 —
 * IdP 는 503 을 재시도 신호로 읽는다 (설계 §6).
 */
public class LockUnavailableException extends RuntimeException {

    public LockUnavailableException(String message) {
        super(message);
    }
}

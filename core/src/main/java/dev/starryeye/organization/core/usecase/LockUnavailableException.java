package dev.starryeye.organization.core.usecase;

/**
 * 락을 잡지 못했거나 쥐고 있던 리스를 잃었다. 호출자는 503 으로 옮긴다 —
 * IdP 는 503 을 재시도 신호로 읽는다 (설계 §6).
 */
public class LockUnavailableException extends RuntimeException {

    public LockUnavailableException(String message) {
        super(message);
    }

    /**
     * 락 자체의 문제가 아니라 저장소 장애로 획득에 실패했을 때 쓴다 (설계 §6 두 번째 행).
     * 원인을 물고 가야 한다 — 여기서 끊으면 로그에 "락을 얻는 중 오류" 만 남고 DynamoDB 가
     * 무엇을 던졌는지가 사라진다.
     */
    public LockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

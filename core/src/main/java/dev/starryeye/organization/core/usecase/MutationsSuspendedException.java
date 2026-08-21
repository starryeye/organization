package dev.starryeye.organization.core.usecase;

/**
 * 재적재가 도는 동안 들어온 변경 요청을 거절할 때 쓴다.
 *
 * <p>커넥터가 이것을 프로토콜의 "잠시 뒤 다시 시도하라" 응답으로 옮긴다 — SCIM 은 503.
 * IdP 는 503 을 재시도 신호로 보므로 프로비저닝이 유실되지 않고, 재시도 시점에는
 * 재적재가 끝난 깨끗한 상태 위에서 처리된다.
 */
public class MutationsSuspendedException extends RuntimeException {

    public MutationsSuspendedException(String message) {
        super(message);
    }
}

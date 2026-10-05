package dev.starryeye.organization.core.port;

import java.time.Duration;

/**
 * 같은 요청을 잠시 뒤 다시 보내면 나을 수 있는 실패(설계 2026-10-05 §3.1, 점검 M4). SCIM 은 503 + {@code Retry-After} 로 옮긴다 — IdP 가 재시도하게.
 * 우리가 만드는 일시 장애(락, 쓰기 차단기, 재시도 상한)가 이것을 단다. 라이브러리 예외는 어댑터의 {@link TemporaryFailureRecognizer} 가 알아본다.
 */
public class TemporaryFailureException extends RuntimeException {

    /** 원인별 시간이 따로 없을 때 — 하위 시스템 장애·부분 실패·리스 상실 */
    public static final Duration 기본_대기 = Duration.ofSeconds(10);

    private final Duration retryAfter;

    public TemporaryFailureException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public TemporaryFailureException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    /** 다시 보내기 전에 기다릴 시간 */
    public Duration retryAfter() {
        return retryAfter;
    }
}

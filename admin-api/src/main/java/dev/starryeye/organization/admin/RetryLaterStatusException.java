package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * 컨트롤러가 낸 "잠시 뒤 다시 보낸다" — 503, 사유는 컨트롤러가 정한 문구, {@code Retry-After} 는 {@link #getHeaders()} 가 싣는다. 관리 API 처리기가
 * problem details 로 바꾸며 그 머리를 응답에 옮긴다({@code InsufficientScopeException} 의 {@code WWW-Authenticate} 와 같은 길이다).
 *
 * <p><b>왜 일시 장애 경로(분류기)로 두지 않나.</b> 그 경로는 detail 을 정한 문구({@code 일시 장애 — 잠시 뒤 다시 시도한다})로 바꾸고 예외 문구를 싣지 않는다.
 * 쓰기 요청의 락 경합은 409 가 아니라 503 이어야 하는데(설계 2026-11-04 §3.1 — 409 는 순환이 쓴다), 락 경합을 그냥 던지면 처리기가 409 로 거른다. 그래서 컨트롤러가
 * 이 예외로 바꿔 던진다. 상태 예외가 처리기의 판정에서 가장 먼저라 원인으로 품은 {@code LockUnavailableException} 에 가려지지 않는다.
 */
public class RetryLaterStatusException extends ResponseStatusException {

    private final long 재시도_초;

    /**
     * @param reason     응답의 detail
     * @param retryAfter 기다릴 시간 — {@code Retry-After} 는 올림한 정수 초이고 1초보다 짧아도 1 이다
     * @param cause      원인. 없으면 null
     */
    public RetryLaterStatusException(String reason, Duration retryAfter, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
        this.재시도_초 = AdminExceptionHandler.재시도_초(retryAfter);
    }

    /**
     * 원인 사슬에 {@link LockUnavailableException} 이 있으면 그것을 503 + 그 예외의 {@code Retry-After} 로 바꾼 예외, 없으면 {@code error} 그대로다.
     * 쓰기 요청의 컨트롤러가 {@code onErrorMap} 에 건다 — 락 경합을 직접 던진 경우뿐 아니라 다른 예외가 원인으로 품은 경우도 처리기가 사슬에서 찾아 409 로
     * 내면 순환과 섞이기 때문이다. 사슬은 처리기와 같은 탐색으로 따라간다(같은 예외를 두 번 보지 않는다). 바뀐 예외는 원래 예외를 원인으로 든다.
     */
    public static Throwable 락_경합이면_바꾼다(Throwable error) {
        return AdminExceptionHandler.원인에서_찾는다(error, LockUnavailableException.class)
                .<Throwable>map(락 -> new RetryLaterStatusException(락.getMessage(), 락.retryAfter(), error))
                .orElse(error);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders 머리 = new HttpHeaders();
        머리.set(HttpHeaders.RETRY_AFTER, String.valueOf(재시도_초));
        return HttpHeaders.readOnlyHttpHeaders(머리);
    }
}

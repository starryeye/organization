package dev.starryeye.organization.scim;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Optional;

/**
 * SCIM Error 응답(설계 §9.3)으로 번역되는 예외.
 *
 * <p>{@code scimType} 은 SCIM 이 규정한 오류 분류다. 404 와 500 에는 해당 분류가 없으므로 null 을 허용한다.
 */
@Getter
public class ScimException extends RuntimeException {

    private final HttpStatus status;
    private final String scimType;
    private final Duration retryAfter;

    public ScimException(HttpStatus status, String scimType, String detail) {
        this(status, scimType, detail, null);
    }

    public ScimException(HttpStatus status, String scimType, String detail, Duration retryAfter) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
        this.retryAfter = retryAfter;
    }

    /** 다시 보내기 전에 기다릴 시간. 503 에만 있다. */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public static ScimException notFound(String detail) {
        return new ScimException(HttpStatus.NOT_FOUND, null, detail);
    }

    public static ScimException invalidSyntax(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidSyntax", detail);
    }

    public static ScimException invalidPath(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidPath", detail);
    }

    /** RFC 7644 §3.12 — 필터 문법이 틀렸거나 지원하지 않는 속성·연산자 조합이다. */
    public static ScimException invalidFilter(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidFilter", detail);
    }

    /** RFC 7644 §3.12 — 값이 없거나 작업과 맞지 않는다. 조회 파라미터(정렬·페이지·속성 선택)의 잘못된 값에 쓴다. */
    public static ScimException invalidValue(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidValue", detail);
    }

    /** RFC 7644 §3.12 — 서비스 제공자가 지원하지 않는 작업이다(서버 루트 조회 등). */
    public static ScimException notImplemented(String detail) {
        return new ScimException(HttpStatus.NOT_IMPLEMENTED, null, detail);
    }

    public static ScimException uniqueness(String detail) {
        return new ScimException(HttpStatus.CONFLICT, "uniqueness", detail);
    }

    /** RFC 7644 §3.5.2.2 — 필수·읽기 전용 속성을 지우려 했다. */
    public static ScimException mutability(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "mutability", detail);
    }

    /** RFC 7644 §3.5.2.3 — 값 경로 필터가 가리킨 값이 없다. */
    public static ScimException noTarget(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "noTarget", detail);
    }

    /** 다시 보내면 나을 수 있다 — 503 + Retry-After(설계 2026-10-05 §3.5). 부분 실패에 쓴다. */
    public static ScimException temporarilyUnavailable(String detail, Duration retryAfter) {
        return new ScimException(HttpStatus.SERVICE_UNAVAILABLE, null, detail, retryAfter);
    }

    /** 커밋 직후 다시 읽기가 빈 경우처럼 설명할 수 없는 상태 — 500 */
    public static ScimException internal(String detail) {
        return new ScimException(HttpStatus.INTERNAL_SERVER_ERROR, null, detail);
    }
}

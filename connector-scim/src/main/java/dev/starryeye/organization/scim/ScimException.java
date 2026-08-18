package dev.starryeye.organization.scim;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * SCIM Error 응답(설계 §9.3)으로 번역되는 예외.
 *
 * <p>{@code scimType} 은 SCIM 이 규정한 오류 분류다. 404 와 500 에는 해당 분류가 없으므로 null 을 허용한다.
 */
@Getter
public class ScimException extends RuntimeException {

    private final HttpStatus status;
    private final String scimType;

    public ScimException(HttpStatus status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
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

    public static ScimException uniqueness(String detail) {
        return new ScimException(HttpStatus.CONFLICT, "uniqueness", detail);
    }

    /** 하위 시스템(OpenFGA/DynamoDB) 실패. IdP 가 재시도하도록 5xx 로 돌려준다. */
    public static ScimException internal(String detail) {
        return new ScimException(HttpStatus.INTERNAL_SERVER_ERROR, null, detail);
    }
}

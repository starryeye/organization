package dev.starryeye.organization.core.usecase;

/**
 * 이미 있는 아이디로 만들거나, 다른 직원의 userName 과 겹치게 만들거나 바꾸려 했다. 호출자는 409 {@code uniqueness} 로 옮긴다
 * (RFC 7644 §3.12 — PUT·PATCH 도 같다). 판단은 전역 쓰기 락 안에서 한다(SCIM 쓰기 락 설계 §3·§4).
 */
public class DirectoryConflictException extends RuntimeException {

    public DirectoryConflictException(String message) {
        super(message);
    }
}

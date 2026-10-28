package dev.starryeye.organization.core.usecase;

/**
 * 유일해야 하는 값이 다른 리소스와 겹친다 — 직원 {@code userName}(대소문자 무시) 또는 조직 {@code externalId}. 그 값의 유일 값 표지판을 쥔 주인이
 * 지금도 그 값을 쓰고 있을 때 {@link UniqueMarkers} 가 던진다(설계 2026-10-14 §4). 아이디는 서버가 발급하므로 아이디 겹침으로는 생기지 않는다.
 * 호출자는 409 {@code uniqueness} 로 옮긴다(RFC 7644 §3.12 — POST·PUT·PATCH 모두). 판단은 전역 쓰기 락 안에서 한다(SCIM 쓰기 락 설계 §3·§4).
 */
public class DirectoryConflictException extends RuntimeException {

    public DirectoryConflictException(String message) {
        super(message);
    }
}

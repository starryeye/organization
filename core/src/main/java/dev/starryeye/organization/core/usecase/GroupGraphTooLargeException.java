package dev.starryeye.organization.core.usecase;

/**
 * 조직 계층이 순환 검사 한도를 넘었다(설계 2026-10-03 §4.2, 점검 P2). 같은 요청은 다시 보내도 늘 넘으므로 호출자는 400 {@code invalidValue}(영구 거절)로
 * 옮긴다 — 500 이면 IdP 가 같은 실패를 되풀이한다.
 */
public class GroupGraphTooLargeException extends RuntimeException {

    public GroupGraphTooLargeException(String message) {
        super(message);
    }
}

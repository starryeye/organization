package dev.starryeye.organization.core.usecase;

/**
 * 유일 값(직원 userName·조직 externalId)이 표지판 키 한도(UTF-8 1024바이트)를 넘는다(설계 2026-10-14 §3.1). 같은 요청은 다시 보내도 늘 넘으므로
 * 호출자는 400 {@code invalidValue}(영구 거절)로 옮긴다 — 500 이면 IdP 가 같은 실패를 되풀이한다.
 */
public class UniqueValueTooLongException extends RuntimeException {

    public UniqueValueTooLongException(String message) {
        super(message);
    }
}

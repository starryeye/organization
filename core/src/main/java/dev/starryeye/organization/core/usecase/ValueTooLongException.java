package dev.starryeye.organization.core.usecase;

/**
 * 키나 색인에 들어갈 값(직원 userName·displayName·externalId, 조직 displayName·externalId, 넣는 멤버의 아이디)이 쓰기 한도를 넘는다(설계 2026-10-14 §3.1,
 * 2026-10-17 §3). 같은 요청은 다시 보내도 늘 넘으므로 호출자는 400 {@code invalidValue}(영구 거절)로 옮긴다 — 500 이면 IdP 가 같은 실패를 되풀이한다.
 *
 * <p>문구에는 어느 속성인지와 한도만 담고 값은 담지 않는다.
 */
public class ValueTooLongException extends RuntimeException {

    private final String attribute;
    private final int limitBytes;

    public ValueTooLongException(String attribute, int limitBytes) {
        super("%s 이(가) %d바이트를 넘습니다".formatted(attribute, limitBytes));
        this.attribute = attribute;
        this.limitBytes = limitBytes;
    }

    /** 한도를 넘은 속성의 이름 — SCIM 속성 이름이다({@code userName}·{@code displayName}·{@code externalId}·{@code members}). */
    public String attribute() {
        return attribute;
    }

    public int limitBytes() {
        return limitBytes;
    }
}

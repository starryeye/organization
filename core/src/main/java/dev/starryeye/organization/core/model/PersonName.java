package dev.starryeye.organization.core.model;

import lombok.With;

/**
 * 직원 이름 — RFC 7643 §4.1.1 {@code name} 의 하위 속성 여섯. 튜플에는 쓰지 않는다(권한과 무관하다).
 *
 * <p>빈 문자열은 "없음"(null)으로 본다 — 저장소는 빈 값을 저장하지 않으므로, 여기서 같은 규칙을 지켜야 저장·되읽기 뒤에도
 * 같은 값이 된다. 여섯 칸이 모두 없으면 {@link #EMPTY} 와 같다.
 */
@With
public record PersonName(
        String formatted,
        String familyName,
        String givenName,
        String middleName,
        String honorificPrefix,
        String honorificSuffix
) {

    public static final PersonName EMPTY = new PersonName(null, null, null, null, null, null);

    public PersonName {
        formatted = present(formatted);
        familyName = present(familyName);
        givenName = present(givenName);
        middleName = present(middleName);
        honorificPrefix = present(honorificPrefix);
        honorificSuffix = present(honorificSuffix);
    }

    private static String present(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}

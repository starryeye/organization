package dev.starryeye.organization.scim;

import java.util.Locale;

/**
 * 대소문자를 가리지 않는 비교(RFC 7643 {@code caseExact=false}). 저장소의 인덱스 키({@code Keys.indexKey})와
 * <b>같은 규칙</b>이어야 한다 — 인덱스로 찾은 후보를 메모리에서 다시 확인할 때 둘이 어긋나면 찾은 것을 버린다.
 */
final class ScimText {

    private ScimText() {
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** 저장된 값이 없으면 어떤 값과도 같지 않다. */
    static boolean sameIgnoringCase(String stored, String asked) {
        return stored != null && lower(stored).equals(lower(asked));
    }
}

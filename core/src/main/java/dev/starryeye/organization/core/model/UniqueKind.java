package dev.starryeye.organization.core.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * SCIM 이 유일하게 지키는 값의 종류(설계 2026-10-14 §3.1). 표지판 줄의 키는 이 규칙으로 만든다 — 대소문자 무시 같은 규칙은 도메인의 몫이라
 * 저장소는 받은 키를 그대로 쓴다.
 */
public enum UniqueKind {

    /** 직원 userName — 대소문자를 가리지 않는다(RFC 7643 caseExact=false). 키는 소문자다. */
    USER_NAME {
        @Override
        public String key(String value) {
            return value.toLowerCase(Locale.ROOT);
        }
    },

    /** 조직 externalId — 대소문자를 가린다(RFC 7643 caseExact). 키는 값 그대로다. */
    GROUP_EXTERNAL_ID {
        @Override
        public String key(String value) {
            return value;
        }
    };

    /** 키의 최대 길이(UTF-8 바이트) — 포트 계약이다. DynamoDB 정렬키 한도와 같다. */
    public static final int MAX_KEY_BYTES = 1024;

    public abstract String key(String value);

    /** 키가 한도 안인가. */
    public static boolean fits(String key) {
        return key.getBytes(StandardCharsets.UTF_8).length <= MAX_KEY_BYTES;
    }
}

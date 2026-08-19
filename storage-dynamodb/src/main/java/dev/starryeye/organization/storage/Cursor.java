package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DynamoDB 의 {@code LastEvaluatedKey} 를 불투명 문자열로 감싼다.
 *
 * <p>이 스키마의 키 속성은 전부 문자열(S)이므로 값만 뽑아 base64 로 감싼다.
 * 이름과 값을 각각 감싸는 이유는, 값에 구분자가 들어 있어도 파싱이 깨지지 않게 하기 위해서다.
 *
 * <p>커서에 원본 키가 담기지만 이 API 는 인증이 없어 어차피 데이터가 열려 있다.
 * 암호화는 인증 사이클에서 함께 다룬다.
 */
final class Cursor {

    private static final String PAIR_SEPARATOR = "|";
    private static final String KEY_VALUE_SEPARATOR = ":";

    private Cursor() {
    }

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        String joined = lastEvaluatedKey.entrySet().stream()
                .map(entry -> b64(entry.getKey()) + KEY_VALUE_SEPARATOR + b64(entry.getValue().s()))
                .collect(Collectors.joining(PAIR_SEPARATOR));
        return b64(joined);
    }

    static Map<String, AttributeValue> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String joined = unb64(cursor);
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            for (String pair : joined.split("\\" + PAIR_SEPARATOR)) {
                String[] parts = pair.split(KEY_VALUE_SEPARATOR, 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("커서 형식이 올바르지 않다");
                }
                key.put(unb64(parts[0]), Attrs.s(unb64(parts[1])));
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("커서를 해석할 수 없다", e);
        }
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}

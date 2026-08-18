package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;

/** AttributeValue 를 만들고 읽는 잡일을 한 곳에 모은다. */
public final class Attrs {

    private Attrs() {
    }

    public static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    public static AttributeValue n(Number value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    public static AttributeValue bool(boolean value) {
        return AttributeValue.builder().bool(value).build();
    }

    public static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || Boolean.TRUE.equals(value.nul()) ? null : value.s();
    }

    public static int integer(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? 0 : Integer.parseInt(value.n());
    }

    public static long longValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? 0L : Long.parseLong(value.n());
    }

    public static boolean flag(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value != null && Boolean.TRUE.equals(value.bool());
    }

    public static Instant instant(Map<String, AttributeValue> item, String name) {
        String raw = str(item, name);
        return raw == null ? null : Instant.parse(raw);
    }

    /** null 이면 아예 넣지 않는다. DynamoDB 는 빈 문자열을 허용하지만 null 은 허용하지 않는다. */
    public static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null && !value.isEmpty()) {
            item.put(name, s(value));
        }
    }
}

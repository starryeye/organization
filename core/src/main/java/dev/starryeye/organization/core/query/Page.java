package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * 커서 기반 한 페이지. {@code nextCursor} 가 null 이면 마지막이다.
 *
 * <p>DynamoDB 는 offset 을 지원하지 않으므로 페이지 번호가 아니라 커서를 쓴다.
 * 커서는 저장소가 만든 불투명 문자열이며 호출자는 해석하지 않는다.
 */
public record Page<T>(List<T> items, String nextCursor) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> Page<T> empty() {
        return new Page<>(List.of(), null);
    }

    public boolean hasNext() {
        return nextCursor != null;
    }
}

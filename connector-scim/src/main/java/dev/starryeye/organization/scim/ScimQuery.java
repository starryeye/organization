package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimSearchRequest;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.List;

/**
 * 조회 파라미터 한 벌 (S-1 설계 §4). URL 쿼리와 {@code .search} 본문이 같은 이 형태로 바뀐다.
 *
 * <p>페이지 규칙(RFC 7644 §3.4.2.4)을 여기서 한 번에 정리한다 — {@code startIndex<1} 은 1, {@code count} 가
 * 없으면 100, 음수는 0, 100 초과는 100. 정렬은 인덱스 키만(§4.3). 잘못된 값은 {@code invalidValue}, 필터는
 * {@code invalidFilter} 다.
 *
 * @param filter     없으면 null — 필터 없는 목록
 * @param startIndex 1 부터
 * @param count      0~100
 */
public record ScimQuery(ScimFilter filter, long startIndex, int count, boolean descending,
                        ScimAttributeProjection projection) {

    /** 페이지 상한이자 기본값. ServiceProviderConfig 의 {@code filter.maxResults} 와 같다. */
    public static final int MAX_COUNT = 100;

    public static ScimQuery fromRequest(ScimResourceType type, ServerRequest request) {
        return of(type,
                request.queryParam("filter").orElse(null),
                number(request.queryParam("startIndex").orElse(null), "startIndex"),
                number(request.queryParam("count").orElse(null), "count"),
                request.queryParam("sortBy").orElse(null),
                request.queryParam("sortOrder").orElse(null),
                ScimAttributeProjection.split(request.queryParam("attributes").orElse(null)),
                ScimAttributeProjection.split(request.queryParam("excludedAttributes").orElse(null)));
    }

    public static ScimQuery fromSearch(ScimResourceType type, ScimSearchRequest body) {
        if (body.schemas() == null || !body.schemas().contains(ScimSchemas.SEARCH_REQUEST)) {
            throw ScimException.invalidSyntax("SearchRequest 스키마가 없습니다: " + ScimSchemas.SEARCH_REQUEST);
        }
        return of(type, body.filter(), body.startIndex(), body.count(), body.sortBy(), body.sortOrder(),
                body.attributes(), body.excludedAttributes());
    }

    static ScimQuery of(ScimResourceType type, String filter, Long startIndex, Long count,
                        String sortBy, String sortOrder, List<String> attributes, List<String> excludedAttributes) {
        ScimFilter parsed = filter == null ? null : ScimFilter.parse(filter, type);
        long start = startIndex == null || startIndex < 1 ? 1 : startIndex;
        int size = count == null ? MAX_COUNT : (int) Math.max(0, Math.min(count, MAX_COUNT));
        checkSortBy(type, sortBy);
        return new ScimQuery(parsed, start, size, descending(sortOrder),
                ScimAttributeProjection.of(type, attributes, excludedAttributes));
    }

    private static Long number(String raw, String name) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ScimException.invalidValue(name + " 는 정수여야 합니다: " + raw);
        }
    }

    private static void checkSortBy(ScimResourceType type, String sortBy) {
        if (sortBy == null) {
            return;
        }
        String local = type.localName(sortBy);
        if (local == null || !local.equals(ScimText.lower(type.sortAttribute()))) {
            throw ScimException.invalidValue(
                    "정렬은 " + type.sortAttribute() + " 로만 할 수 있습니다: " + sortBy);
        }
    }

    private static boolean descending(String sortOrder) {
        if (sortOrder == null || sortOrder.equalsIgnoreCase("ascending")) {
            return false;
        }
        if (sortOrder.equalsIgnoreCase("descending")) {
            return true;
        }
        throw ScimException.invalidValue("sortOrder 는 ascending 또는 descending 입니다: " + sortOrder);
    }
}

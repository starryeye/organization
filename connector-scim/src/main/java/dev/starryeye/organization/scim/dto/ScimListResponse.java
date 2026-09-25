package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.scim.ScimQuery;
import dev.starryeye.organization.scim.ScimSchemas;

import java.util.List;

/**
 * RFC 7644 §3.4.2 ListResponse. {@code Resources} 는 {@code count=0} 이면 없고, 결과가 0건이면 빈 배열이다.
 *
 * @param itemsPerPage 실제로 담은 수
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimListResponse(
        List<String> schemas,
        long totalResults,
        long startIndex,
        int itemsPerPage,
        @JsonProperty("Resources") List<JsonNode> resources
) {

    public static ScimListResponse of(ScimQuery query, long totalResults, List<? extends JsonNode> resources) {
        return new ScimListResponse(List.of(ScimSchemas.LIST_RESPONSE), totalResults, query.startIndex(),
                resources.size(), query.count() == 0 ? null : List.copyOf(resources));
    }
}

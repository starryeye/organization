package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** RFC 7644 §3.4.3 — {@code POST /.search} 본문. URL 조회와 같은 파라미터를 본문으로 보낸다. */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimSearchRequest(
        List<String> schemas,
        List<String> attributes,
        List<String> excludedAttributes,
        String filter,
        String sortBy,
        String sortOrder,
        Long startIndex,
        Long count
) {
}

package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1, 설계 2026-10-06 §5.1) — 클래스 단위로 걸어 관리 API 의 JSON 은 그대로다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimUser(
        List<String> schemas,
        String id,
        String externalId,
        String userName,
        ScimName name,
        String displayName,
        List<ScimEmail> emails,
        Boolean active,
        ScimMeta meta
) {
}

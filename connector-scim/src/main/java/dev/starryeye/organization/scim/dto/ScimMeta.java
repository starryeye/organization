package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 리소스 메타(RFC 7643 §3.1). 필드 순서는 RFC 예시 순서다. 시각은 ISO-8601 UTC 문자열({@code Instant.toString()})이고, 모르면 싣지 않는다
 * (설계 2026-10-09 §4.4). 요청 본문의 meta 는 읽기 전용이라 쓰지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimMeta(String resourceType, String created, String lastModified, String location) {
}

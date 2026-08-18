package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param value op 에 따라 모양이 달라진다 — members 배열이거나 단일 스칼라이거나
 *              path 없는 부분 리소스다. 그래서 Object 로 받고 적용 시점에 해석한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimOperation(String op, String path, Object value) {
}

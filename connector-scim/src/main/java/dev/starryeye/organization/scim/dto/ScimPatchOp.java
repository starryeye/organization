package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * @param operations SCIM 스펙이 필드명을 대문자 "Operations" 로 규정한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimPatchOp(
        List<String> schemas,
        @JsonProperty("Operations") List<ScimOperation> operations
) {
}

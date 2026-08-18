package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimGroup(
        List<String> schemas,
        String id,
        String externalId,
        String displayName,
        List<ScimMember> members,
        ScimMeta meta
) {
}

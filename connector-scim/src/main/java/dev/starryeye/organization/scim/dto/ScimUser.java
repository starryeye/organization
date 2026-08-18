package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
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

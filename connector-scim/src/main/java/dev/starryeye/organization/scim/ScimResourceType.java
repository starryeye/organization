package dev.starryeye.organization.scim;

import java.util.Locale;
import java.util.Set;

/**
 * 조회가 다루는 두 리소스. 스키마 URN, 정렬 가능한 속성(인덱스 키), 응답에 담기는 속성 경로를 한 곳에 둔다.
 *
 * <p>속성 경로는 우리 DTO({@code ScimUser}/{@code ScimGroup})가 실제로 내보내는 것만이다 — {@code attributes}·
 * {@code excludedAttributes} 에 그 밖의 이름이 오면 거절한다(S-1 설계 §4.5).
 */
public enum ScimResourceType {

    USER(ScimSchemas.USER, "userName", Set.of(
            "schemas", "id", "externalid", "username",
            "name", "name.formatted", "name.familyname", "name.givenname",
            "displayname", "emails", "emails.value", "emails.type", "emails.primary",
            "active", "meta", "meta.resourcetype", "meta.location")),

    GROUP(ScimSchemas.GROUP, "displayName", Set.of(
            "schemas", "id", "externalid", "displayname",
            "members", "members.value", "members.type", "members.display",
            "meta", "meta.resourcetype", "meta.location"));

    private final String schemaUrn;
    private final String sortAttribute;
    private final Set<String> attributes;

    ScimResourceType(String schemaUrn, String sortAttribute, Set<String> attributes) {
        this.schemaUrn = schemaUrn;
        this.sortAttribute = sortAttribute;
        this.attributes = attributes;
    }

    public String schemaUrn() {
        return schemaUrn;
    }

    /** 정렬할 수 있는 유일한 속성 — 목록을 이어 읽는 인덱스의 키다(S-1 설계 §4.3). */
    public String sortAttribute() {
        return sortAttribute;
    }

    /** 응답에 담기는 속성 경로. 소문자다. */
    public Set<String> attributes() {
        return attributes;
    }

    /**
     * 이 리소스의 core 스키마 URN 이 붙었으면 떼고 소문자로 준다. 다른 스키마의 URN 이면 null.
     * 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1).
     */
    public String localName(String path) {
        String prefix = schemaUrn + ":";
        String local = path.regionMatches(true, 0, prefix, 0, prefix.length())
                ? path.substring(prefix.length())
                : path;
        return local.startsWith("urn:") ? null : local.toLowerCase(Locale.ROOT);
    }
}

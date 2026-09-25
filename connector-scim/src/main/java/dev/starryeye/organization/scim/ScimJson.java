package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * SCIM DTO 를 JSON 트리로 바꾼다. 속성 선택(RFC 7644 §3.9)이 트리에서 속성을 지우기 때문이다.
 * DTO 의 직렬화 규칙({@code @JsonInclude(NON_NULL)})은 애노테이션이라 이 매퍼에도 그대로 적용된다.
 */
final class ScimJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScimJson() {
    }

    static ObjectNode tree(Object resource) {
        return MAPPER.valueToTree(resource);
    }
}

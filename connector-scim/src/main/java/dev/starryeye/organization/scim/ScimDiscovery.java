package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * {@code /ResourceTypes}·{@code /Schemas} — 이 서버가 다루는 자원과 속성을 IdP 에 알린다(RFC 7644 §4, 설계 2026-10-17 §5).
 *
 * <p>자원은 바뀌지 않으므로 클래스를 불러올 때 한 번 짓는다. 우리가 실제로 저장하고 돌려주는 속성만 담는다 —
 * 응답 DTO({@code ScimUser}·{@code ScimGroup} 과 하위 레코드)와의 일치는 {@code ScimDiscoverySchemaAlignmentTest} 가 지킨다.
 * 공통 속성({@code id}·{@code externalId}·{@code meta})과 enterprise 확장은 담지 않는다.
 */
final class ScimDiscovery {

    private static final String BASE = "/scim/v2";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static final ObjectNode USER_TYPE = 자원_종류("User", "/Users", "User Account", ScimSchemas.USER);
    private static final ObjectNode GROUP_TYPE = 자원_종류("Group", "/Groups", "Group", ScimSchemas.GROUP);
    private static final ObjectNode USER_SCHEMA = 스키마(ScimSchemas.USER, "User", "User Account", 직원_속성());
    private static final ObjectNode GROUP_SCHEMA = 스키마(ScimSchemas.GROUP, "Group", "Group", 조직_속성());

    private static final List<ObjectNode> RESOURCE_TYPES = List.of(USER_TYPE, GROUP_TYPE);
    private static final List<ObjectNode> SCHEMAS = List.of(USER_SCHEMA, GROUP_SCHEMA);

    private ScimDiscovery() {
    }

    static Mono<ServerResponse> resourceTypes(ServerRequest request) {
        return 목록(request, RESOURCE_TYPES);
    }

    static Mono<ServerResponse> resourceType(ServerRequest request) {
        return 단건(request, RESOURCE_TYPES, request.pathVariable("name"), "자원 종류");
    }

    static Mono<ServerResponse> schemas(ServerRequest request) {
        return 목록(request, SCHEMAS);
    }

    static Mono<ServerResponse> schema(ServerRequest request) {
        return 단건(request, SCHEMAS, request.pathVariable("id"), "스키마");
    }

    /** 정렬 시험이 응답 DTO 와 맞대 본다 — 공유 원본이 아니라 복사본이다 */
    static JsonNode userSchema() {
        return USER_SCHEMA.deepCopy();
    }

    /** 정렬 시험이 응답 DTO 와 맞대 본다 — 공유 원본이 아니라 복사본이다 */
    static JsonNode groupSchema() {
        return GROUP_SCHEMA.deepCopy();
    }

    private static Mono<ServerResponse> 목록(ServerRequest request, List<ObjectNode> 자원) {
        if (filter_있다(request)) {
            return Mono.error(filter_거절());
        }
        // 공유 원본이 아니라 복사본을 내보낸다 — 직렬화 쪽이 값을 바꿔도 다음 응답이 달라지지 않는다
        var body = new ScimListResponse(List.of(ScimSchemas.LIST_RESPONSE), 자원.size(), 1, 자원.size(),
                자원.stream().<JsonNode>map(ObjectNode::deepCopy).toList());
        return ServerResponse.ok().contentType(ScimRouter.SCIM_JSON).bodyValue(body);
    }

    /** 이름·URN 은 대소문자를 가리지 않는다(설계 2026-10-17 §5) */
    private static Mono<ServerResponse> 단건(ServerRequest request, List<ObjectNode> 자원, String 아이디, String 종류) {
        if (filter_있다(request)) {
            return Mono.error(filter_거절());
        }
        return 자원.stream()
                .filter(하나 -> 하나.get("id").asText().equalsIgnoreCase(아이디))
                .findFirst()
                .map(하나 -> ServerResponse.ok().contentType(ScimRouter.SCIM_JSON).bodyValue(하나.deepCopy()))
                .orElseGet(() -> Mono.error(ScimException.notFound("없는 " + 종류 + "입니다: " + 아이디)));
    }

    /** RFC 7644 §4 — 필터를 받지 않는 엔드포인트에 {@code filter} 가 오면 403 이다. 다른 쿼리 파라미터는 무시한다. */
    private static boolean filter_있다(ServerRequest request) {
        return request.queryParam("filter").isPresent();
    }

    private static ScimException filter_거절() {
        return ScimException.forbidden("이 엔드포인트는 filter 를 받지 않습니다 — RFC 7644 §4");
    }

    // ---- 자원 짓기 ----

    private static ObjectNode 자원_종류(String 이름, String 엔드포인트, String 설명, String 스키마_urn) {
        ObjectNode type = JSON.objectNode();
        type.putArray("schemas").add(ScimSchemas.RESOURCE_TYPE);
        type.put("id", 이름);
        type.put("name", 이름);
        type.put("endpoint", 엔드포인트);
        type.put("description", 설명);
        type.put("schema", 스키마_urn);
        meta(type, "ResourceType", BASE + "/ResourceTypes/" + 이름);
        return type;
    }

    private static ObjectNode 스키마(String urn, String 이름, String 설명, ArrayNode 속성) {
        ObjectNode schema = JSON.objectNode();
        schema.putArray("schemas").add(ScimSchemas.SCHEMA);
        schema.put("id", urn);
        schema.put("name", 이름);
        schema.put("description", 설명);
        schema.set("attributes", 속성);
        meta(schema, "Schema", BASE + "/Schemas/" + urn);
        return schema;
    }

    private static void meta(ObjectNode 자원, String resourceType, String location) {
        ObjectNode meta = 자원.putObject("meta");
        meta.put("resourceType", resourceType);
        meta.put("location", location);
    }

    /**
     * 지금 코드의 실제 동작대로다 — userName 의 유일성은 표지판이 지키고, 대소문자는 키를 소문자로 만들어 가리지 않는다.
     * 속성마다 짧은 {@code description} 을 단다(RFC 7643 §7). 말은 RFC 의 예처럼 영어다.
     */
    private static ArrayNode 직원_속성() {
        return 배열(
                문자열("userName", "Unique name of the user, case-insensitive").put("required", true).put("uniqueness", "server"),
                복합("name", "The components of the user's name", false,
                        문자열("formatted", "Full name, formatted for display"),
                        문자열("familyName", "Family name (last name)"),
                        문자열("givenName", "Given name (first name)"),
                        문자열("middleName", "Middle name"),
                        문자열("honorificPrefix", "Honorific prefix, such as Ms."),
                        문자열("honorificSuffix", "Honorific suffix, such as III")),
                문자열("displayName", "Name of the user, suitable for display"),
                // 한 개만 저장하고 type work·primary true 로 돌려준다
                복합("emails", "Email address of the user; only one is stored", true,
                        문자열("value", "Email address"),
                        정규값(문자열("type", "Label of the email address, always work"), "work"),
                        불리언("primary", "Whether this is the primary email, always true")),
                불리언("active", "Whether the user account is active"));
    }

    private static ArrayNode 조직_속성() {
        return 배열(
                // POST 가 displayName 을 요구하지 않는다
                문자열("displayName", "Name of the group, suitable for display"),
                // display·$ref 는 돌려주지 않아 알리지 않는다. 멤버는 넣고 빼기만 하고 바꾸지 않는다.
                // value 가 없는 멤버는 400 invalidSyntax 라 필수다(ScimMapper.memberId·ScimPatchApplier). type 은 없으면 현재 상태로 판정하므로 필수가 아니다
                복합("members", "Members of the group, users or other groups", true,
                        문자열("value", "Identifier of the member").put("required", true).put("mutability", "immutable"),
                        정규값(문자열("type", "Type of the member, User or Group"), "User", "Group").put("mutability", "immutable")));
    }

    private static ArrayNode 배열(ObjectNode... 속성) {
        ArrayNode array = JSON.arrayNode();
        for (ObjectNode 하나 : 속성) {
            array.add(하나);
        }
        return array;
    }

    /** 성질의 기본값 — 쓰고 읽을 수 있고(readWrite), 기본으로 돌려주고(default), 유일하지 않고(none), 필수가 아니다. */
    private static ObjectNode 속성(String 이름, String 설명, String type, boolean multiValued) {
        ObjectNode attribute = JSON.objectNode();
        attribute.put("name", 이름);
        attribute.put("type", type);
        attribute.put("multiValued", multiValued);
        attribute.put("description", 설명);
        attribute.put("required", false);
        if ("string".equals(type)) {
            // RFC 7643 §7 — caseExact 는 문자열 속성에만 있다
            attribute.put("caseExact", false);
        }
        attribute.put("mutability", "readWrite");
        attribute.put("returned", "default");
        attribute.put("uniqueness", "none");
        return attribute;
    }

    private static ObjectNode 문자열(String 이름, String 설명) {
        return 속성(이름, 설명, "string", false);
    }

    private static ObjectNode 불리언(String 이름, String 설명) {
        return 속성(이름, 설명, "boolean", false);
    }

    private static ObjectNode 복합(String 이름, String 설명, boolean multiValued, ObjectNode... 하위) {
        ObjectNode attribute = 속성(이름, 설명, "complex", multiValued);
        attribute.set("subAttributes", 배열(하위));
        return attribute;
    }

    private static ObjectNode 정규값(ObjectNode attribute, String... 값) {
        ArrayNode canonicalValues = attribute.putArray("canonicalValues");
        for (String 하나 : 값) {
            canonicalValues.add(하나);
        }
        return attribute;
    }
}

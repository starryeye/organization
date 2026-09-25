package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code attributes}·{@code excludedAttributes} (RFC 7644 §3.9) — 응답에 담을 속성을 고른다.
 *
 * <p>리소스를 돌려주는 모든 응답에 적용한다. 이름은 최상위 속성과 한 단계 하위 속성이며, 대소문자를 가리지
 * 않고 URN 이 붙어도 된다. {@code id} 와 {@code schemas} 는 어느 경우에도 남긴다({@code id} 는 RFC 7643 에서
 * {@code returned: always}). 둘을 함께 주거나 모르는 이름을 주면 {@code invalidValue} 다(S-1 설계 §4.5).
 */
public final class ScimAttributeProjection {

    private static final Set<String> ALWAYS = Set.of("id", "schemas");
    private static final ScimAttributeProjection ALL = new ScimAttributeProjection(Set.of(), Set.of());

    /** 비어 있으면 제한 없음. 소문자 경로. */
    private final Set<String> include;
    private final Set<String> exclude;

    private ScimAttributeProjection(Set<String> include, Set<String> exclude) {
        this.include = include;
        this.exclude = exclude;
    }

    public static ScimAttributeProjection all() {
        return ALL;
    }

    public static ScimAttributeProjection of(ScimResourceType type, List<String> attributes, List<String> excluded) {
        List<String> chosen = attributes == null ? List.of() : attributes;
        List<String> dropped = excluded == null ? List.of() : excluded;
        if (!chosen.isEmpty() && !dropped.isEmpty()) {
            throw ScimException.invalidValue("attributes 와 excludedAttributes 는 함께 쓸 수 없습니다");
        }
        return new ScimAttributeProjection(names(type, chosen), names(type, dropped));
    }

    public static ScimAttributeProjection fromRequest(ScimResourceType type, ServerRequest request) {
        return of(type,
                split(request.queryParam("attributes").orElse(null)),
                split(request.queryParam("excludedAttributes").orElse(null)));
    }

    /** URL 쿼리의 쉼표 목록. 비어 있으면 빈 목록. */
    public static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static Set<String> names(ScimResourceType type, List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : raw) {
            String local = type.localName(name);
            if (local == null || !type.attributes().contains(local)) {
                throw ScimException.invalidValue("알 수 없는 속성입니다: " + name);
            }
            result.add(local);
        }
        return Set.copyOf(result);
    }

    /** 이 최상위 속성이 응답에 남는가. 조직의 {@code members} 를 읽을지 정할 때 쓴다. */
    public boolean includes(String attribute) {
        String name = attribute.toLowerCase(Locale.ROOT);
        if (exclude.contains(name)) {
            return false;
        }
        if (include.isEmpty() || ALWAYS.contains(name) || include.contains(name)) {
            return true;
        }
        return include.stream().anyMatch(path -> path.startsWith(name + "."));
    }

    public ObjectNode apply(ObjectNode resource) {
        if (!include.isEmpty()) {
            for (String field : fieldNames(resource)) {
                String name = field.toLowerCase(Locale.ROOT);
                if (ALWAYS.contains(name) || include.contains(name)) {
                    continue;
                }
                Set<String> subs = subAttributes(include, name);
                if (subs.isEmpty()) {
                    resource.remove(field);
                } else {
                    eachObject(resource.get(field), node -> retain(node, subs));
                }
            }
        }
        for (String path : exclude) {
            int dot = path.indexOf('.');
            if (dot < 0) {
                if (!ALWAYS.contains(path)) {
                    removeIgnoringCase(resource, path);
                }
            } else {
                String parent = field(resource, path.substring(0, dot));
                if (parent != null) {
                    String sub = path.substring(dot + 1);
                    eachObject(resource.get(parent), node -> removeIgnoringCase(node, sub));
                }
            }
        }
        return resource;
    }

    private static Set<String> subAttributes(Set<String> paths, String parent) {
        return paths.stream()
                .filter(path -> path.startsWith(parent + "."))
                .map(path -> path.substring(parent.length() + 1))
                .collect(Collectors.toSet());
    }

    /** 복합 속성은 객체이거나 객체의 배열이다({@code emails}, {@code members}). */
    private static void eachObject(JsonNode node, java.util.function.Consumer<ObjectNode> action) {
        if (node instanceof ObjectNode object) {
            action.accept(object);
        } else if (node != null && node.isArray()) {
            node.forEach(element -> {
                if (element instanceof ObjectNode object) {
                    action.accept(object);
                }
            });
        }
    }

    private static void retain(ObjectNode node, Set<String> subs) {
        for (String field : fieldNames(node)) {
            if (!subs.contains(field.toLowerCase(Locale.ROOT))) {
                node.remove(field);
            }
        }
    }

    private static void removeIgnoringCase(ObjectNode node, String name) {
        String field = field(node, name);
        if (field != null) {
            node.remove(field);
        }
    }

    private static String field(ObjectNode node, String name) {
        for (String field : fieldNames(node)) {
            if (field.equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

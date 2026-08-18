package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM PATCH 연산을 도메인 객체에 적용한다.
 *
 * <p>설계 §10.1 이 정한 여섯 가지 형태만 지원한다. 일반적인 SCIM 필터 문법 파서는 만들지 않는다.
 * 지원하지 않는 path 는 조용히 무시하지 않고 거절한다 — IdP 는 2xx 를 받으면 반영됐다고 믿고
 * 다시 보내지 않으므로, 무시는 영구적인 상태 불일치가 된다.
 */
public final class ScimPatchApplier {

    /** {@code members[value eq "kim"]} 한 가지 패턴만 인식한다. 따옴표는 큰/작은 둘 다 받는다. */
    private static final Pattern MEMBER_VALUE_FILTER =
            Pattern.compile("^members\\[\\s*value\\s+eq\\s+[\"'](?<value>[^\"']+)[\"']\\s*]$");

    private ScimPatchApplier() {
    }

    public static DirectoryGroup applyToGroup(DirectoryGroup before, ScimPatchOp patch) {
        DirectoryGroup current = before;
        for (ScimOperation operation : operations(patch)) {
            current = applyOne(current, operation);
        }
        return current;
    }

    public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch) {
        DirectoryUser current = before;
        for (ScimOperation operation : operations(patch)) {
            current = applyOne(current, operation);
        }
        return current;
    }

    private static List<ScimOperation> operations(ScimPatchOp patch) {
        if (patch == null || patch.operations() == null || patch.operations().isEmpty()) {
            throw ScimException.invalidSyntax("PATCH 요청에 Operations 가 없습니다");
        }
        return patch.operations();
    }

    // ---------- 그룹 ----------

    private static DirectoryGroup applyOne(DirectoryGroup group, ScimOperation operation) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireReplaceOrAdd(op, operation.op());
            return mergeGroupAttributes(group, asAttributeMap(operation.value()));
        }

        Matcher filter = MEMBER_VALUE_FILTER.matcher(path.trim());
        if (filter.matches()) {
            if (!op.equals("remove")) {
                throw ScimException.invalidPath(
                        "members 필터는 remove 에만 지원합니다: op=" + operation.op() + ", path=" + path);
            }
            String target = filter.group("value");
            Set<MemberRef> members = new LinkedHashSet<>(group.members());
            members.removeIf(member -> member.id().equals(target));
            return withMembers(group, members);
        }

        if (path.trim().equals("members")) {
            return switch (op) {
                case "add" -> {
                    Set<MemberRef> members = new LinkedHashSet<>(group.members());
                    members.addAll(toMemberRefs(operation.value()));
                    yield withMembers(group, members);
                }
                case "remove" -> withMembers(group, Set.of());
                case "replace" -> withMembers(group, toMemberRefs(operation.value()));
                default -> throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + operation.op());
            };
        }

        if (path.trim().equals("displayName")) {
            requireReplaceOrAdd(op, operation.op());
            return new DirectoryGroup(group.id(), group.externalId(),
                    asString(operation.value()), group.members());
        }

        throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
    }

    private static DirectoryGroup mergeGroupAttributes(DirectoryGroup group, Map<String, Object> attributes) {
        String displayName = attributes.containsKey("displayName")
                ? asString(attributes.get("displayName"))
                : group.displayName();
        Set<MemberRef> members = attributes.containsKey("members")
                ? toMemberRefs(attributes.get("members"))
                : group.members();
        return new DirectoryGroup(group.id(), group.externalId(), displayName, members);
    }

    private static DirectoryGroup withMembers(DirectoryGroup group, Set<MemberRef> members) {
        return new DirectoryGroup(group.id(), group.externalId(), group.displayName(), members);
    }

    // ---------- 직원 ----------

    private static DirectoryUser applyOne(DirectoryUser user, ScimOperation operation) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireReplaceOrAdd(op, operation.op());
            return mergeUserAttributes(user, asAttributeMap(operation.value()));
        }

        requireReplaceOrAdd(op, operation.op());
        return switch (path.trim()) {
            case "active" -> new DirectoryUser(user.id(), user.externalId(), user.userName(),
                    user.displayName(), user.email(), asBoolean(operation.value()));
            case "displayName" -> new DirectoryUser(user.id(), user.externalId(), user.userName(),
                    asString(operation.value()), user.email(), user.active());
            case "userName" -> new DirectoryUser(user.id(), user.externalId(),
                    asString(operation.value()), user.displayName(), user.email(), user.active());
            default -> throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
        };
    }

    private static DirectoryUser mergeUserAttributes(DirectoryUser user, Map<String, Object> attributes) {
        return new DirectoryUser(
                user.id(),
                user.externalId(),
                attributes.containsKey("userName") ? asString(attributes.get("userName")) : user.userName(),
                attributes.containsKey("displayName") ? asString(attributes.get("displayName")) : user.displayName(),
                user.email(),
                attributes.containsKey("active") ? asBoolean(attributes.get("active")) : user.active());
    }

    // ---------- 값 해석 ----------

    private static String normalizeOp(String op) {
        if (op == null || op.isBlank()) {
            throw ScimException.invalidSyntax("op 가 비어 있습니다");
        }
        return op.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireReplaceOrAdd(String normalizedOp, String originalOp) {
        if (!normalizedOp.equals("replace") && !normalizedOp.equals("add")) {
            throw ScimException.invalidSyntax("이 path 에는 replace 또는 add 만 지원합니다: " + originalOp);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<MemberRef> toMemberRefs(Object value) {
        if (!(value instanceof List<?> raw)) {
            throw ScimException.invalidSyntax("members 값은 배열이어야 합니다");
        }
        Set<MemberRef> members = new LinkedHashSet<>();
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> map)) {
                throw ScimException.invalidSyntax("members 원소는 객체여야 합니다");
            }
            Object id = map.get("value");
            if (id == null) {
                throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
            }
            Object type = map.get("type");
            // SCIM 에서 type 은 선택 필드다. 없으면 User 로 간주한다.
            boolean isGroup = type != null && type.toString().equalsIgnoreCase("Group");
            members.add(isGroup ? MemberRef.group(id.toString()) : MemberRef.user(id.toString()));
        }
        return members;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asAttributeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw ScimException.invalidSyntax("path 없는 연산의 값은 객체여야 합니다");
        }
        return (Map<String, Object>) map;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        throw ScimException.invalidSyntax("boolean 값이 아닙니다: " + value);
    }
}

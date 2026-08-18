package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
@Slf4j
public final class ScimPatchApplier {

    /** {@code members[value eq "kim"]} 한 가지 패턴만 인식한다. 따옴표는 큰/작은 둘 다 받는다. */
    private static final Pattern MEMBER_VALUE_FILTER =
            Pattern.compile("^members\\[\\s*value\\s+eq\\s+[\"'](?<value>[^\"']+)[\"']\\s*]$");

    private ScimPatchApplier() {
    }

    /**
     * {@code type} 이 빠진 멤버나 {@code members[value eq "..."]} 필터를 만나면 현재상태를
     * 조회해야 하므로 반환값이 {@link Mono} 다. 연산은 배열 순서대로 누적 적용된다.
     */
    public static Mono<DirectoryGroup> applyToGroup(DirectoryGroup before, ScimPatchOp patch,
                                                    MemberTypeResolver resolver) {
        Mono<DirectoryGroup> current = Mono.just(before);
        for (ScimOperation operation : operations(patch)) {
            current = current.flatMap(group -> applyOne(group, operation, resolver));
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

    private static Mono<DirectoryGroup> applyOne(DirectoryGroup group, ScimOperation operation,
                                                 MemberTypeResolver resolver) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireReplaceOrAdd(op, operation.op());
            return mergeGroupAttributes(group, asAttributeMap(operation.value()), resolver);
        }

        Matcher filter = MEMBER_VALUE_FILTER.matcher(path.trim());
        if (filter.matches()) {
            if (!op.equals("remove")) {
                throw ScimException.invalidPath(
                        "members 필터는 remove 에만 지원합니다: op=" + operation.op() + ", path=" + path);
            }
            return removeMemberById(group, IdNormalizer.normalize(filter.group("value")), resolver);
        }

        if (path.trim().equals("members")) {
            return switch (op) {
                case "add" -> toMemberRefs(operation.value(), resolver).map(added -> {
                    Set<MemberRef> members = new LinkedHashSet<>(group.members());
                    members.addAll(added);
                    return withMembers(group, members);
                });
                case "remove" -> Mono.just(withMembers(group, Set.of()));
                case "replace" -> toMemberRefs(operation.value(), resolver)
                        .map(members -> withMembers(group, members));
                default -> throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + operation.op());
            };
        }

        if (path.trim().equals("displayName")) {
            requireReplaceOrAdd(op, operation.op());
            return Mono.just(new DirectoryGroup(group.id(), group.externalId(),
                    asString(operation.value()), group.members()));
        }

        throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
    }

    /**
     * {@code members[value eq "x"]} 는 필터에 종류가 없다. 조직코드와 직원 아이디는 서로 다른
     * 네임스페이스라 같은 값이 둘 다 멤버일 수 있는데, id 만 보고 지우면 둘 다 사라진다.
     *
     * <p>대부분의 경우 그 id 를 가진 멤버는 이 조직 안에 하나뿐이므로 조회 없이 그것만 지운다.
     * 직원과 하위 조직이 같은 id 로 동시에 멤버인 진짜 모호한 경우에만 현재상태로 종류를
     * 판정해 한쪽만 지운다 — 어느 쪽이든 SCIM 필터로는 구분할 수 없으니 경고를 남긴다.
     */
    private static Mono<DirectoryGroup> removeMemberById(DirectoryGroup group, String target,
                                                         MemberTypeResolver resolver) {
        List<MemberRef> matching = group.members().stream()
                .filter(member -> member.id().equals(target))
                .toList();
        if (matching.size() <= 1) {
            Set<MemberRef> members = new LinkedHashSet<>(group.members());
            members.removeAll(matching);
            return Mono.just(withMembers(group, members));
        }
        log.warn("members[value eq \"{}\"] 가 직원과 하위 조직 양쪽에 걸립니다. 현재상태로 한쪽만 지웁니다: 조직={}",
                target, group.id());
        return resolver.resolve(target).map(type -> {
            Set<MemberRef> members = new LinkedHashSet<>(group.members());
            members.remove(new MemberRef(type, target));
            return withMembers(group, members);
        });
    }

    private static Mono<DirectoryGroup> mergeGroupAttributes(DirectoryGroup group,
                                                             Map<String, Object> attributes,
                                                             MemberTypeResolver resolver) {
        String displayName = attributes.containsKey("displayName")
                ? asString(attributes.get("displayName"))
                : group.displayName();
        Mono<Set<MemberRef>> members = attributes.containsKey("members")
                ? toMemberRefs(attributes.get("members"), resolver)
                : Mono.just(group.members());
        return members.map(resolved ->
                new DirectoryGroup(group.id(), group.externalId(), displayName, resolved));
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

    /**
     * {@code value} 는 {@link ScimMapper} 와 같은 규칙으로 정규화한다 — 정규화를 빼먹으면
     * 그 멤버는 저장되고 응답에도 실리지만 튜플은 하나도 만들어지지 않는다.
     */
    private static Mono<Set<MemberRef>> toMemberRefs(Object value, MemberTypeResolver resolver) {
        if (!(value instanceof List<?> raw)) {
            throw ScimException.invalidSyntax("members 값은 배열이어야 합니다");
        }
        if (raw.isEmpty()) {
            return Mono.just(Set.of());
        }
        return Flux.fromIterable(raw)
                .concatMap(element -> memberRef(element, resolver))
                .collect(LinkedHashSet<MemberRef>::new, Set::add)
                .map(members -> members);
    }

    private static Mono<MemberRef> memberRef(Object element, MemberTypeResolver resolver) {
        if (!(element instanceof Map<?, ?> map)) {
            return Mono.error(ScimException.invalidSyntax("members 원소는 객체여야 합니다"));
        }
        Object rawId = map.get("value");
        if (rawId == null || rawId.toString().isBlank()) {
            return Mono.error(ScimException.invalidSyntax("members 원소에 value 가 없습니다"));
        }
        String id = IdNormalizer.normalize(rawId.toString());
        Object type = map.get("type");
        // SCIM 에서 type 은 선택 필드다. 없으면 추측하지 않고 현재상태로 판정한다.
        if (type == null || type.toString().isBlank()) {
            return resolver.resolve(id).map(resolved -> new MemberRef(resolved, id));
        }
        return Mono.just(type.toString().equalsIgnoreCase("Group")
                ? MemberRef.group(id)
                : MemberRef.user(id));
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

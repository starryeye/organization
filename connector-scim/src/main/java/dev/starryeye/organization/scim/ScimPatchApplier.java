package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM PATCH 연산을 도메인 객체에 적용한다.
 *
 * <p>직원은 우리가 저장하는 속성 전부, 조직은 members·displayName(S-3 설계 §7.2). 속성 이름은
 * 대소문자를 가리지 않는다. 지원하지 않는 path 는 조용히 무시하지 않고 거절한다 — IdP 는 2xx 를
 * 받으면 반영됐다고 믿고 다시 보내지 않으므로, 무시는 영구적인 상태 불일치가 된다.
 */
@Slf4j
public final class ScimPatchApplier {

    /** {@code members[value eq "kim"]} 한 가지 패턴만 인식한다. 따옴표는 큰/작은 둘 다 받는다. */
    private static final Pattern MEMBER_VALUE_FILTER = Pattern.compile(
            "^members\\[\\s*value\\s+eq\\s+[\"'](?<value>[^\"']+)[\"']\\s*]$", Pattern.CASE_INSENSITIVE);

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

        if (path.trim().equalsIgnoreCase("members")) {
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

        if (path.trim().equalsIgnoreCase("displayName")) {
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
        String displayName = has(attributes, "displayName")
                ? asString(attribute(attributes, "displayName"))
                : group.displayName();
        Mono<Set<MemberRef>> members = has(attributes, "members")
                ? toMemberRefs(attribute(attributes, "members"), resolver)
                : Mono.just(group.members());
        return members.map(resolved ->
                new DirectoryGroup(group.id(), group.externalId(), displayName, resolved));
    }

    private static DirectoryGroup withMembers(DirectoryGroup group, Set<MemberRef> members) {
        return new DirectoryGroup(group.id(), group.externalId(), group.displayName(), members);
    }

    // ---------- 직원 ----------

    /** {@code emails[type eq "work"]} 와 {@code .value} — 우리는 이메일을 하나만 담고 type "work" 로 내보낸다. */
    private static final Pattern EMAIL_FILTER = Pattern.compile(
            "^emails\\[\\s*type\\s+eq\\s+\"(?<type>[^\"]*)\"\\s*](?<value>\\.value)?$", Pattern.CASE_INSENSITIVE);

    /** {@code name} 의 하위 속성 여섯. 키는 소문자. */
    private static final Map<String, BiFunction<PersonName, String, PersonName>> NAME_PARTS = Map.of(
            "formatted", PersonName::withFormatted,
            "familyname", PersonName::withFamilyName,
            "givenname", PersonName::withGivenName,
            "middlename", PersonName::withMiddleName,
            "honorificprefix", PersonName::withHonorificPrefix,
            "honorificsuffix", PersonName::withHonorificSuffix);

    private static DirectoryUser applyOne(DirectoryUser user, ScimOperation operation) {
        String op = requireKnownOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireReplaceOrAdd(op, operation.op());
            return mergeUserAttributes(user, asAttributeMap(operation.value()));
        }

        String target = path.trim();
        Matcher email = EMAIL_FILTER.matcher(target);
        if (email.matches()) {
            return applyWorkEmail(user, op, operation, email, path);
        }

        boolean remove = op.equals("remove");
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name.")) {
            BiFunction<PersonName, String, PersonName> part = NAME_PARTS.get(lower.substring("name.".length()));
            if (part == null) {
                throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
            }
            return user.withName(part.apply(user.name(), remove ? null : asString(operation.value())));
        }
        return switch (lower) {
            case "username" -> {
                if (remove) {
                    throw ScimException.mutability("userName 은 필수라 지울 수 없습니다");
                }
                yield user.withUserName(asString(operation.value()));
            }
            case "displayname" -> user.withDisplayName(remove ? null : asString(operation.value()));
            case "externalid" -> user.withExternalId(remove ? null : asString(operation.value()));
            // active 가 없으면 활성이다 — POST 에 active 가 없을 때와 같은 규칙
            case "active" -> user.withActive(remove || asBoolean(operation.value()));
            case "name" -> user.withName(remove ? PersonName.EMPTY : mergeName(user.name(), asAttributeMap(operation.value())));
            case "emails" -> user.withEmail(remove ? null : primaryEmail(operation.value()));
            default -> throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
        };
    }

    /** RFC 7644 §3.5.2.3 — 이메일이 없는데 replace 하면 가리킬 값이 없다. add 는 새로 담는다. */
    private static DirectoryUser applyWorkEmail(DirectoryUser user, String op, ScimOperation operation,
                                                Matcher filter, String path) {
        if (!filter.group("type").equalsIgnoreCase("work")) {
            throw ScimException.invalidPath("이메일은 type \"work\" 하나만 담습니다: " + path);
        }
        if (op.equals("remove")) {
            return user.withEmail(null);
        }
        if (op.equals("replace") && user.email() == null) {
            throw ScimException.noTarget("바꿀 work 이메일이 없습니다: " + path);
        }
        String value = filter.group("value") != null
                ? asString(operation.value())
                : asString(attribute(asAttributeMap(operation.value()), "value"));
        return user.withEmail(value);
    }

    /** 경로 없는 add/replace — 우리가 저장하는 속성 전부를 반영한다. 모르는 키는 POST 처럼 무시한다. */
    private static DirectoryUser mergeUserAttributes(DirectoryUser user, Map<String, Object> attributes) {
        DirectoryUser merged = user;
        if (has(attributes, "userName")) {
            merged = merged.withUserName(asString(attribute(attributes, "userName")));
        }
        if (has(attributes, "displayName")) {
            merged = merged.withDisplayName(asString(attribute(attributes, "displayName")));
        }
        if (has(attributes, "externalId")) {
            merged = merged.withExternalId(asString(attribute(attributes, "externalId")));
        }
        if (has(attributes, "active")) {
            merged = merged.withActive(asBoolean(attribute(attributes, "active")));
        }
        if (has(attributes, "name")) {
            merged = merged.withName(mergeName(merged.name(), asAttributeMap(attribute(attributes, "name"))));
        }
        if (has(attributes, "emails")) {
            merged = merged.withEmail(primaryEmail(attribute(attributes, "emails")));
        }
        return merged;
    }

    /** RFC 7644 §3.5.2.3 — 준 하위 속성만 바꾸고 나머지는 그대로 둔다. 모르는 하위 속성은 무시한다. */
    private static PersonName mergeName(PersonName current, Map<String, Object> parts) {
        PersonName merged = current;
        for (Map.Entry<String, Object> entry : parts.entrySet()) {
            BiFunction<PersonName, String, PersonName> part = NAME_PARTS.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (part != null) {
                merged = part.apply(merged, asString(entry.getValue()));
            }
        }
        return merged;
    }

    /** POST 와 같은 규칙 — primary 가 참인 것, 없으면 첫째. 빈 목록이면 없음. */
    private static String primaryEmail(Object value) {
        if (!(value instanceof List<?> emails)) {
            throw ScimException.invalidSyntax("emails 값은 배열이어야 합니다");
        }
        Map<String, Object> chosen = null;
        for (Object element : emails) {
            Map<String, Object> email = asAttributeMap(element);
            if (chosen == null || Boolean.TRUE.equals(attribute(email, "primary"))) {
                chosen = email;
                if (Boolean.TRUE.equals(attribute(email, "primary"))) {
                    break;
                }
            }
        }
        return chosen == null ? null : asString(attribute(chosen, "value"));
    }

    /** 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1). */
    private static Object attribute(Map<String, Object> attributes, String name) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean has(Map<String, Object> attributes, String name) {
        return attributes.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private static String requireKnownOp(String op) {
        String normalized = normalizeOp(op);
        if (!normalized.equals("add") && !normalized.equals("replace") && !normalized.equals("remove")) {
            throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + op);
        }
        return normalized;
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
            throw ScimException.invalidSyntax("값은 객체여야 합니다");
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

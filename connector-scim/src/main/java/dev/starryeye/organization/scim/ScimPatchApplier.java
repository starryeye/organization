package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM PATCH 연산을 도메인 객체에 적용한다.
 *
 * <p>직원은 우리가 저장하는 속성 전부, 조직은 members·displayName·externalId 를 다룬다(S-3 설계 §7.2, 설계 2026-10-06 §4). 속성 이름은
 * 대소문자를 가리지 않는다. 저장하는 속성은 엄격하게 적용한다. RFC 7643 이 정의했지만 저장하지 않는 직원 속성은 path 로 와도 받아서
 * 버린다 — 저장하지도 돌려주지도 않으니 불일치가 보일 곳이 없다. 거절하면 오히려 같은 요청의 비활성화까지 막힌다(설계 2026-10-06 §3,
 * 점검 M5). RFC 에 없는 path 만 400 이다. 조직은 {@link GroupChange} 로 정리만 하고 적용은 유스케이스가 한다.
 */
public final class ScimPatchApplier {

    /**
     * {@code members[...]}. 대괄호 안은 목록 조회와 같은 {@link ScimFilter} 가 읽는다 — RFC 8259 JSON 문자열(큰따옴표만 구분자, 이스케이프 풀기).
     * 정규식으로 따옴표를 흉내 내면 값 안의 작은따옴표({@code o'brien})에서 끊긴다(점검 C4).
     */
    private static final Pattern MEMBER_FILTER = Pattern.compile("^members\\[(?<filter>.*)]$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ScimPatchApplier() {
    }

    /** 값 붙은 remove members — RFC 7644 에 없는 모양이라 "그 멤버만" 으로 추측하지 않는다(조직 멤버 PATCH 설계 §2·§7). */
    static final String REMOVE_WITH_VALUE = "members 에서 멤버를 골라 빼려면 path 에 필터를 쓰세요: members[value eq \"<id>\"]. "
            + "Microsoft Entra ID 는 SCIM 테넌트 URL 에 ?aadOptscim062020 을 붙이면 이 형식으로 보냅니다.";

    /**
     * 조직 PATCH 를 <b>저장소를 읽지 않고</b> {@link GroupChange} 로 정리한다(조직 멤버 PATCH 설계 §4). 연산은 배열 순서대로 쌓인다.
     * {@code type} 이 빠진 멤버의 종류만, 요청의 아이디를 먼저 모아 {@code resolver} 로 한 번에 판정한다(설계 2026-10-03 §3.2). 멤버십을 보고 하는 판단(지금 멤버인가, id 빼기가 직원·하위
     * 조직 중 무엇인가)은 유스케이스가 락 안에서 한다 — 락 밖에서 읽은 목록으로 계산하면 동시에 온 PATCH 가 서로를 지운다.
     */
    public static Mono<GroupChange> toGroupChange(ScimPatchOp patch, MemberTypeResolver resolver) {
        List<ScimOperation> operations = operations(patch);
        return Mono.defer(() -> {
                    Set<String> 모름 = new LinkedHashSet<>();
                    operations.forEach(operation -> 모름.addAll(typeless(operation)));
                    return resolver.resolveAll(모름);
                })
                .map(종류 -> {
                    GroupChange change = GroupChange.delta();
                    for (ScimOperation operation : operations) {
                        change = applyOne(change, operation, 종류);
                    }
                    return change;
                });
    }

    /** 직원 PATCH. 받아서 버린 속성의 이름({@link ScimRfcAttributes} 의 정규 이름이나 {@code other})을 {@code 버림} 으로 넘긴다(설계 2026-10-06 §3.3). */
    public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch, Consumer<String> 버림) {
        DirectoryUser current = before;
        for (ScimOperation operation : operations(patch)) {
            current = applyOne(current, operation, 버림);
        }
        return current;
    }

    public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch) {
        return applyToUser(before, patch, 이름 -> { });
    }

    private static List<ScimOperation> operations(ScimPatchOp patch) {
        if (patch == null || patch.operations() == null || patch.operations().isEmpty()) {
            throw ScimException.invalidSyntax("PATCH 요청에 Operations 가 없습니다");
        }
        return patch.operations();
    }

    // ---------- 그룹 ----------

    /**
     * 이 연산이 넣는 멤버 중 {@code type} 이 없는 아이디(정규화한 값, 설계 2026-10-03 §3.2). 모양이 틀린 연산은 비워 두고 {@link #applyOne} 이 거절한다 —
     * 여기서는 예외를 던지지 않는다.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> typeless(ScimOperation operation) {
        String op = operation.op() == null ? "" : operation.op().trim().toLowerCase(Locale.ROOT);
        if (!op.equals("add") && !op.equals("replace")) {
            return Set.of();
        }
        String path = operation.path();
        Object members = null;
        if (path == null || path.isBlank()) {
            if (operation.value() instanceof Map<?, ?> map) {
                members = attribute((Map<String, Object>) map, "members");
            }
        } else if (path.trim().equalsIgnoreCase("members")) {
            members = operation.value();
        }
        if (!(members instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) raw;
            Object id = attribute(map, "value");
            Object type = attribute(map, "type");
            if (id != null && !id.toString().isBlank() && (type == null || type.toString().isBlank())) {
                ids.add(IdNormalizer.normalize(id.toString()));
            }
        }
        return ids;
    }

    private static GroupChange applyOne(GroupChange change, ScimOperation operation, Map<String, MemberType> 종류) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireTarget(op);
            requireReplaceOrAdd(op, operation.op());
            return mergeGroupAttributes(change, op, asAttributeMap(operation.value()), 종류);
        }

        Matcher filter = MEMBER_FILTER.matcher(path.trim());
        if (filter.matches()) {
            if (!op.equals("remove")) {
                throw ScimException.invalidPath(
                        "members 필터는 remove 에만 지원합니다: op=" + operation.op() + ", path=" + path);
            }
            return change.removingId(memberId(filter.group("filter"), path));
        }

        if (path.trim().equalsIgnoreCase("members")) {
            return switch (op) {
                case "add" -> change.adding(toMemberRefs(operation.value(), 종류));
                case "remove" -> {
                    // RFC 7644 §3.5.2.2 — 필터 없는 remove 는 전원 삭제다. remove 의 value 는 RFC 가 정하지 않은 칸이다
                    if (operation.value() != null) {
                        throw ScimException.invalidValue(REMOVE_WITH_VALUE);
                    }
                    yield change.replacing(Set.of());
                }
                case "replace" -> change.replacing(toMemberRefs(operation.value(), 종류));
                default -> throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + operation.op());
            };
        }

        if (path.trim().equalsIgnoreCase("displayName")) {
            requireReplaceOrAdd(op, operation.op());
            return change.renamed(asString(operation.value()));
        }

        throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
    }

    /**
     * {@code members[value eq "<아이디>"]} 의 아이디. 한 항 {@code value eq "…"} 말고는 받지 않는다 — 문법 오류는 {@link ScimFilter} 가
     * {@code invalidFilter} 로, 모양이 다르면 여기서 {@code invalidPath} 로 거절한다. 빈 값은 아이디가 될 수 없어 {@code invalidPath} 다
     * ({@link IdNormalizer} 의 예외가 500 으로 새지 않게).
     */
    private static String memberId(String filter, String path) {
        ScimFilter parsed = ScimFilter.parse(filter, ScimResourceType.GROUP);
        if (parsed.terms().size() != 1) {
            throw ScimException.invalidPath("members 필터는 value eq \"<아이디>\" 한 항만 지원합니다: " + path);
        }
        ScimFilter.Term term = parsed.terms().get(0);
        if (!"value".equals(term.attribute()) || !(term.value() instanceof String value) || value.isBlank()) {
            throw ScimException.invalidPath("members 필터는 value eq \"<아이디>\" 한 항만 지원합니다: " + path);
        }
        return IdNormalizer.normalize(value);
    }

    /**
     * 경로 없는 add/replace 의 {@code members} — {@code op} 가 {@code add} 면 증분 추가, 아니면(=replace) 전체 교체다
     * (RFC 7644 §3.5.2.1, 최종 리뷰 F2). {@code op} 는 이미 {@link #normalizeOp} 로 소문자다.
     */
    private static GroupChange mergeGroupAttributes(GroupChange change, String op, Map<String, Object> attributes,
                                                    Map<String, MemberType> 종류) {
        GroupChange renamed = has(attributes, "displayName")
                ? change.renamed(asString(attribute(attributes, "displayName")))
                : change;
        if (!has(attributes, "members")) {
            return renamed;
        }
        Set<MemberRef> members = toMemberRefs(attribute(attributes, "members"), 종류);
        return op.equals("add") ? renamed.adding(members) : renamed.replacing(members);
    }

    // ---------- 직원 ----------

    /** {@code emails[type eq "work"]} 와 {@code .value} — 우리는 이메일을 하나만 담고 type "work" 로 내보낸다. */
    private static final Pattern EMAIL_FILTER = Pattern.compile(
            "^emails\\[\\s*type\\s+eq\\s+\"(?<type>[^\"]*)\"\\s*](?<value>\\.value)?$", Pattern.CASE_INSENSITIVE);

    /** 코어 스키마 URN 접두(RFC 7643 §3.10) — path·경로 없는 값의 키 모두에서 대소문자 없이 뗀다(F1). */
    private static final String CORE_USER_URN = "urn:ietf:params:scim:schemas:core:2.0:user:";

    /** {@code name} 의 하위 속성 여섯. 키는 소문자. */
    private static final Map<String, BiFunction<PersonName, String, PersonName>> NAME_PARTS = Map.of(
            "formatted", PersonName::withFormatted,
            "familyname", PersonName::withFamilyName,
            "givenname", PersonName::withGivenName,
            "middlename", PersonName::withMiddleName,
            "honorificprefix", PersonName::withHonorificPrefix,
            "honorificsuffix", PersonName::withHonorificSuffix);

    private static DirectoryUser applyOne(DirectoryUser user, ScimOperation operation, Consumer<String> 버림) {
        String op = requireKnownOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireTarget(op);
            requireReplaceOrAdd(op, operation.op());
            return mergeUserAttributes(op, user, asAttributeMap(operation.value()), 버림);
        }

        Optional<DirectoryUser> applied = applyPath(user, op, path.trim(), operation.value());
        if (applied.isPresent()) {
            return applied.get();
        }
        // 저장하지 않는 속성 — RFC 가 정의한 것이면 받아서 버리고, 모르는 것만 거절한다(설계 2026-10-06 §3.1)
        String 버린것 = ScimRfcAttributes.userAttribute(path)
                .orElseThrow(() -> ScimException.invalidPath("지원하지 않는 path 입니다: " + path));
        버림.accept(버린것);
        return user;
    }

    /**
     * path 형식과 경로 없는 값의 키 하나를 <b>같은 규칙</b>으로 해석한다(F1) — 저장하지 않아 못 적용하면
     * {@link Optional#empty()}. 그 뒤는 부르는 쪽이 정한다 — path 형식은 RFC 가 정의한 속성이면 받아서 버리고 아니면 400
     * {@code invalidPath}, 경로 없는 값은 그 키를 무시하고 원래 값을 지킨다(설계 2026-10-06 §3).
     */
    private static Optional<DirectoryUser> applyPath(DirectoryUser user, String op, String path, Object value) {
        String target = stripCoreUrn(path);
        Matcher email = EMAIL_FILTER.matcher(target);
        if (email.matches()) {
            if (!email.group("type").equalsIgnoreCase("work")) {
                return Optional.empty();
            }
            return Optional.of(applyWorkEmail(user, op, value, email, path));
        }

        boolean remove = op.equals("remove");
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name.")) {
            BiFunction<PersonName, String, PersonName> part = NAME_PARTS.get(lower.substring("name.".length()));
            if (part == null) {
                return Optional.empty();
            }
            return Optional.of(user.withName(part.apply(user.name(), remove ? null : asString(value))));
        }
        return switch (lower) {
            case "username" -> Optional.of(applyUserName(user, remove, value));
            case "displayname" -> Optional.of(user.withDisplayName(remove ? null : asString(value)));
            case "externalid" -> Optional.of(user.withExternalId(remove ? null : asString(value)));
            // active 가 없으면 활성이다 — POST 에 active 가 없을 때와 같은 규칙
            case "active" -> Optional.of(user.withActive(remove || asBoolean(value)));
            case "name" -> Optional.of(user.withName(remove ? PersonName.EMPTY : mergeName(user.name(), asAttributeMap(value))));
            case "emails" -> Optional.of(user.withEmail(remove ? null : primaryEmail(value)));
            default -> Optional.empty();
        };
    }

    /** {@code userName} remove 는 필수 속성이라 400 {@code mutability}, 빈 값은 400 {@code invalidValue}(F3, RFC 7644 §3.12). */
    private static DirectoryUser applyUserName(DirectoryUser user, boolean remove, Object value) {
        if (remove) {
            throw ScimException.mutability("userName 은 필수라 지울 수 없습니다");
        }
        String userName = asString(value);
        if (userName == null || userName.isBlank()) {
            throw ScimException.invalidValue("userName 은 필수입니다 — 빈 값으로 바꿀 수 없습니다");
        }
        return user.withUserName(userName);
    }

    /** 코어 스키마 URN 접두를 대소문자 없이 뗀다(F1) — 확장 스키마(enterprise 등) 접두는 그대로 두어 모르는 경로가 된다. */
    private static String stripCoreUrn(String path) {
        if (path.length() > CORE_USER_URN.length()
                && path.substring(0, CORE_USER_URN.length()).equalsIgnoreCase(CORE_USER_URN)) {
            return path.substring(CORE_USER_URN.length());
        }
        return path;
    }

    /** RFC 7644 §3.5.2.3 — 이메일이 없는데 replace 하면 가리킬 값이 없다. add 는 새로 담는다. */
    private static DirectoryUser applyWorkEmail(DirectoryUser user, String op, Object value,
                                                Matcher filter, String path) {
        if (op.equals("remove")) {
            return user.withEmail(null);
        }
        if (op.equals("replace") && user.email() == null) {
            throw ScimException.noTarget("바꿀 work 이메일이 없습니다: " + path);
        }
        String resolved = filter.group("value") != null
                ? asString(value)
                : asString(attribute(asAttributeMap(value), "value"));
        return user.withEmail(resolved);
    }

    /**
     * 경로 없는 add/replace — 값 객체의 키마다 {@code (op, path=키, value=값)} 연산 하나로 보고
     * {@link #applyPath} 로 적용한다(F1). 모르는 키는 지금처럼 무시한다(설계 2026-10-06 §3.4). 그 이름은 표의 정규 이름이나 {@code other} 로 알린다 —
     * 요청 문자열을 메트릭 태그로 넘기지 않는다. Jackson 은 값 객체를 {@code LinkedHashMap} 으로 주므로 키 순서대로 누적 적용된다.
     */
    private static DirectoryUser mergeUserAttributes(String op, DirectoryUser user, Map<String, Object> attributes,
                                                     Consumer<String> 버림) {
        DirectoryUser merged = user;
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Optional<DirectoryUser> applied = applyPath(merged, op, entry.getKey(), entry.getValue());
            if (applied.isPresent()) {
                merged = applied.get();
            } else {
                버림.accept(ScimRfcAttributes.userAttribute(entry.getKey()).orElse(ScimRfcAttributes.OTHER));
            }
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
        return op.trim().toLowerCase(Locale.ROOT);
    }

    /** path 없는 remove — 무엇을 지울지 가리키지 않았다. RFC 7644 §3.5.2.2 가 400 noTarget 으로 정했다(점검 S11). */
    private static void requireTarget(String normalizedOp) {
        if (normalizedOp.equals("remove")) {
            throw ScimException.noTarget("remove 에는 path 가 있어야 합니다");
        }
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
    private static Set<MemberRef> toMemberRefs(Object value, Map<String, MemberType> 종류) {
        if (!(value instanceof List<?> raw)) {
            throw ScimException.invalidSyntax("members 값은 배열이어야 합니다");
        }
        Set<MemberRef> members = new LinkedHashSet<>();
        for (Object element : raw) {
            members.add(memberRef(element, 종류));
        }
        return members;
    }

    @SuppressWarnings("unchecked")
    private static MemberRef memberRef(Object element, Map<String, MemberType> 종류) {
        if (!(element instanceof Map<?, ?> rawMap)) {
            throw ScimException.invalidSyntax("members 원소는 객체여야 합니다");
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        Object rawId = attribute(map, "value");
        if (rawId == null || rawId.toString().isBlank()) {
            throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
        }
        String id = IdNormalizer.normalize(rawId.toString());
        Object type = attribute(map, "type");
        // SCIM 에서 type 은 선택 필드다. 없으면 추측하지 않고, 요청 앞에서 모아 현재상태로 판정한 결과를 쓴다
        if (type == null || type.toString().isBlank()) {
            return new MemberRef(종류.getOrDefault(id, MemberType.USER), id);
        }
        return type.toString().equalsIgnoreCase("Group") ? MemberRef.group(id) : MemberRef.user(id);
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

    /**
     * {@code active} 값 — JSON boolean, 또는 문자열 {@code "true"}/{@code "false"}(대소문자 무관, Entra 가 문자열로 보낸다)만 받는다.
     * 그 밖을 거짓으로 읽으면 조용히 비활성화하므로 400 이다(설계 2026-10-06 §5.2, 점검 S8).
     */
    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw ScimException.invalidValue("active 는 true 또는 false 여야 합니다: " + value);
    }
}

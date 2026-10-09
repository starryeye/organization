package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.model.ResourceTimes;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimMeta;
import dev.starryeye.organization.scim.dto.ScimName;
import dev.starryeye.organization.scim.dto.ScimUser;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SCIM DTO 와 도메인 모델을 오간다.
 *
 * <p>아이디({@link DirectoryUser#id()}·{@link DirectoryGroup#id()})와 속성({@code userName}·{@code externalId}·
 * {@code displayName})은 의도적으로 분리돼 있다. 아이디는 튜플에 쓰이는 식별자이고 나머지는 바뀔 수 있는 속성이다.
 * 아이디는 서버가 발급해 부르는 쪽이 넘기고(RFC 7643 §3.1, 설계 2026-10-04 §3.1), 본문의 {@code id} 는 쓰지 않는다.
 * {@code externalId} 는 속성으로만 둔다.
 */
public final class ScimMapper {

    private ScimMapper() {
    }

    // ---------- SCIM → 도메인 ----------

    /** {@code id} 는 부르는 쪽이 정한다 — POST 는 서버가 발급한 UUID, PUT 은 경로의 id(설계 2026-10-04 §3.1). 본문의 {@code id} 는 쓰지 않는다. */
    public static DirectoryUser toDirectoryUser(ScimUser scim, String id) {
        if (scim.userName() == null || scim.userName().isBlank()) {
            throw ScimException.invalidSyntax("userName 은 필수입니다");
        }
        return new DirectoryUser(
                id,
                scim.externalId(),
                scim.userName(),
                firstNonBlank(scim.displayName(), formatted(scim), scim.userName()),
                primaryEmail(scim.emails()),
                // SCIM 에서 active 는 선택 필드다. 없으면 활성으로 본다.
                scim.active() == null || scim.active(),
                toPersonName(scim.name()));
    }

    /** 보낸 그대로 담는다(S-3 설계 §7.1). 없으면 이름 없음. */
    public static PersonName toPersonName(ScimName name) {
        if (name == null) {
            return PersonName.EMPTY;
        }
        return new PersonName(name.formatted(), name.familyName(), name.givenName(),
                name.middleName(), name.honorificPrefix(), name.honorificSuffix());
    }

    /** 저장된 그대로 돌려준다. 이름이 없으면 null — 응답에 {@code name} 을 넣지 않는다. */
    public static ScimName toScimName(PersonName name) {
        if (PersonName.EMPTY.equals(name)) {
            return null;
        }
        return new ScimName(name.formatted(), name.familyName(), name.givenName(),
                name.middleName(), name.honorificPrefix(), name.honorificSuffix());
    }

    /**
     * {@code id} 는 {@link #toDirectoryUser} 와 같다 — 부르는 쪽이 정하고 본문의 {@code id}·{@code externalId} 는 쓰지 않는다.
     * <p>{@code type} 이 빠진 멤버가 있으면 {@code resolver} 로 현재상태를 조회해야 하므로
     * 반환값이 {@link Mono} 다. 빠진 아이디는 본문에서 먼저 모아 한 번에 판정한다(설계 2026-10-03 §3.2).
     * {@code type} 이 모두 명시돼 있으면 조회는 일어나지 않는다.
     */
    public static Mono<DirectoryGroup> toDirectoryGroup(ScimGroup scim, String id, MemberTypeResolver resolver) {
        return Mono.defer(() -> {
            List<ScimMember> members = scim.members() == null ? List.of() : scim.members();
            Set<String> 모름 = new LinkedHashSet<>();
            for (ScimMember member : members) {
                String memberId = memberId(member);
                if (member.type() == null || member.type().isBlank()) {
                    모름.add(memberId);
                }
            }
            return resolver.resolveAll(모름)
                    .map(종류 -> new DirectoryGroup(id, scim.externalId(), scim.displayName(), toMemberRefs(members, 종류)));
        });
    }

    /** value 가 없으면 {@code invalidSyntax}. 정규화 규칙은 아래 {@link #toMemberRefs} 자바독 참고. */
    private static String memberId(ScimMember member) {
        if (member.value() == null || member.value().isBlank()) {
            throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
        }
        return IdNormalizer.normalize(member.value());
    }

    /**
     * {@code members[].value} 도 {@code userName}/{@code externalId} 과 똑같이 정규화한다.
     * 정규화하지 않으면 IdP 가 우리가 발급한 id 를 그대로 돌려주지 않을 때 그 멤버는
     * DynamoDB 에 저장되고 201/200 응답에도 실려 나가지만 튜플은 하나도 만들어지지 않는다 —
     * {@link dev.starryeye.organization.core.tuple.TupleMapper} 가 스냅샷에서 그 id 를 찾지
     * 못해 경고만 남기고 건너뛰기 때문이다. IdP 는 아무 권한도 주지 못한 프로비저닝을
     * 성공으로 기록하게 된다.
     */
    private static Set<MemberRef> toMemberRefs(List<ScimMember> members, Map<String, MemberType> 종류) {
        Set<MemberRef> refs = new LinkedHashSet<>();
        for (ScimMember member : members) {
            String id = memberId(member);
            // SCIM 에서 type 은 선택 필드다. 없으면 추측하지 않고, 앞에서 모아 현재상태로 판정한 결과를 쓴다.
            if (member.type() == null || member.type().isBlank()) {
                refs.add(new MemberRef(종류.getOrDefault(id, MemberType.USER), id));
            } else {
                refs.add(member.type().equalsIgnoreCase("Group") ? MemberRef.group(id) : MemberRef.user(id));
            }
        }
        return refs;
    }

    private static String formatted(ScimUser scim) {
        return scim.name() == null ? null : scim.name().formatted();
    }

    private static String primaryEmail(List<ScimEmail> emails) {
        if (emails == null || emails.isEmpty()) {
            return null;
        }
        return emails.stream()
                .filter(email -> Boolean.TRUE.equals(email.primary()))
                .findFirst()
                .orElse(emails.get(0))
                .value();
    }

    // ---------- 도메인 → SCIM ----------

    public static ScimUser toScimUser(DirectoryUser user, ResourceTimes times) {
        List<ScimEmail> emails = user.email() == null
                ? List.of()
                : List.of(new ScimEmail(user.email(), "work", true));
        return new ScimUser(
                List.of(ScimSchemas.USER),
                user.id(),
                user.externalId(),
                user.userName(),
                toScimName(user.name()),
                user.displayName(),
                emails,
                user.active(),
                meta("User", times, userLocation(user.id())));
    }

    public static ScimGroup toScimGroup(DirectoryGroup group) {
        List<ScimMember> members = group.members().stream()
                .map(ScimMapper::toScimMember)
                .toList();
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                group.id(),
                group.externalId(),
                group.displayName(),
                members,
                meta("Group", ResourceTimes.UNKNOWN, groupLocation(group.id())));
    }

    /** 멤버 하나 — 조직 응답과 흘려 쓰는 응답이 같은 모양을 쓴다. */
    public static ScimMember toScimMember(MemberRef ref) {
        return new ScimMember(ref.id(), ref.type() == MemberType.GROUP ? "Group" : "User", null);
    }

    /** 멤버 없이 조직을 그린다 — {@code members} 가 응답에 필요 없을 때 멤버 줄을 읽지 않기 위해서다. */
    public static ScimGroup toScimGroup(GroupHeader header) {
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                header.id(),
                header.externalId(),
                header.displayName(),
                null,
                meta("Group", ResourceTimes.UNKNOWN, groupLocation(header.id())));
    }

    /** 리소스 위치 — 본문 {@code meta.location} 과 POST 201 의 {@code Location} 이 같은 값을 쓴다(RFC 7644 §3.3). 아이디는 서버 발급 UUID 라 인코딩할 글자가 없다(④-1). */
    public static String userLocation(String id) {
        return "/scim/v2/Users/" + id;
    }

    public static String groupLocation(String id) {
        return "/scim/v2/Groups/" + id;
    }

    /** 리소스 메타 — 시각은 모르면 싣지 않는다(설계 2026-10-09 §4.4). */
    private static ScimMeta meta(String resourceType, ResourceTimes times, String location) {
        return new ScimMeta(resourceType, text(times.created()), text(times.lastModified()), location);
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}

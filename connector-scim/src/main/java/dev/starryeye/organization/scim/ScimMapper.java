package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimMeta;
import dev.starryeye.organization.scim.dto.ScimName;
import dev.starryeye.organization.scim.dto.ScimUser;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SCIM DTO 와 도메인 모델을 오간다.
 *
 * <p>조직코드({@link DirectoryGroup#id()})와 조직명({@link DirectoryGroup#displayName()})은
 * 의도적으로 분리돼 있다. 코드는 튜플에 쓰이는 식별자이고 이름은 개편 때마다 바뀌는 속성이다.
 * SCIM Group 에는 둘을 나눌 칸이 없어 {@code externalId} 를 코드로 채택한다(설계 §4.3).
 */
@Slf4j
public final class ScimMapper {

    private ScimMapper() {
    }

    // ---------- SCIM → 도메인 ----------

    public static DirectoryUser toDirectoryUser(ScimUser scim) {
        if (scim.userName() == null || scim.userName().isBlank()) {
            throw ScimException.invalidSyntax("userName 은 필수입니다");
        }
        return new DirectoryUser(
                IdNormalizer.normalize(scim.userName()),
                scim.externalId(),
                scim.userName(),
                firstNonBlank(scim.displayName(), formatted(scim), scim.userName()),
                primaryEmail(scim.emails()),
                // SCIM 에서 active 는 선택 필드다. 없으면 활성으로 본다.
                scim.active() == null || scim.active());
    }

    /**
     * {@code type} 이 빠진 멤버가 있으면 {@code resolver} 로 현재상태를 조회해야 하므로
     * 반환값이 {@link Mono} 다. {@code type} 이 모두 명시돼 있으면 조회는 일어나지 않는다.
     */
    public static Mono<DirectoryGroup> toDirectoryGroup(ScimGroup scim, MemberTypeResolver resolver) {
        String code = organizationCode(scim);
        return toMemberRefs(scim.members(), resolver)
                .map(members -> new DirectoryGroup(code, scim.externalId(), scim.displayName(), members));
    }

    private static String organizationCode(ScimGroup scim) {
        String source = firstNonBlank(scim.externalId(), scim.id());
        if (source != null) {
            return IdNormalizer.normalize(source);
        }
        String generated = UUID.randomUUID().toString();
        log.warn("SCIM Group 에 externalId 도 id 도 없어 조직코드를 발급합니다: displayName='{}', 발급된 코드='{}'",
                scim.displayName(), generated);
        return generated;
    }

    /**
     * {@code members[].value} 도 {@code userName}/{@code externalId} 과 똑같이 정규화한다.
     * 정규화하지 않으면 IdP 가 우리가 발급한 id 를 그대로 돌려주지 않을 때 그 멤버는
     * DynamoDB 에 저장되고 201/200 응답에도 실려 나가지만 튜플은 하나도 만들어지지 않는다 —
     * {@link dev.starryeye.organization.core.tuple.TupleMapper} 가 스냅샷에서 그 id 를 찾지
     * 못해 경고만 남기고 건너뛰기 때문이다. IdP 는 아무 권한도 주지 못한 프로비저닝을
     * 성공으로 기록하게 된다.
     */
    private static Mono<Set<MemberRef>> toMemberRefs(List<ScimMember> members, MemberTypeResolver resolver) {
        if (members == null || members.isEmpty()) {
            return Mono.just(Set.of());
        }
        return Flux.fromIterable(members)
                .concatMap(member -> memberRef(member, resolver))
                .collect(LinkedHashSet<MemberRef>::new, Set::add)
                .map(refs -> refs);
    }

    private static Mono<MemberRef> memberRef(ScimMember member, MemberTypeResolver resolver) {
        if (member.value() == null || member.value().isBlank()) {
            return Mono.error(ScimException.invalidSyntax("members 원소에 value 가 없습니다"));
        }
        String id = IdNormalizer.normalize(member.value());
        // SCIM 에서 type 은 선택 필드다. 없으면 추측하지 않고 현재상태로 판정한다.
        if (member.type() == null || member.type().isBlank()) {
            return resolver.resolve(id).map(type -> new MemberRef(type, id));
        }
        return Mono.just(member.type().equalsIgnoreCase("Group")
                ? MemberRef.group(id)
                : MemberRef.user(id));
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

    public static ScimUser toScimUser(DirectoryUser user) {
        List<ScimEmail> emails = user.email() == null
                ? List.of()
                : List.of(new ScimEmail(user.email(), "work", true));
        return new ScimUser(
                List.of(ScimSchemas.USER),
                user.id(),
                user.externalId(),
                user.userName(),
                new ScimName(user.displayName(), null, null),
                user.displayName(),
                emails,
                user.active(),
                new ScimMeta("User", "/scim/v2/Users/" + user.id()));
    }

    public static ScimGroup toScimGroup(DirectoryGroup group) {
        List<ScimMember> members = group.members().stream()
                .map(ref -> new ScimMember(ref.id(),
                        ref.type() == MemberType.GROUP ? "Group" : "User", null))
                .toList();
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                group.id(),
                group.externalId(),
                group.displayName(),
                members,
                new ScimMeta("Group", "/scim/v2/Groups/" + group.id()));
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

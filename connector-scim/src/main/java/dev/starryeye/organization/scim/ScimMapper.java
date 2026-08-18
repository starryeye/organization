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

    public static DirectoryGroup toDirectoryGroup(ScimGroup scim) {
        return new DirectoryGroup(
                organizationCode(scim),
                scim.externalId(),
                scim.displayName(),
                toMemberRefs(scim.members()));
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

    private static Set<MemberRef> toMemberRefs(List<ScimMember> members) {
        if (members == null) {
            return Set.of();
        }
        Set<MemberRef> refs = new LinkedHashSet<>();
        for (ScimMember member : members) {
            if (member.value() == null || member.value().isBlank()) {
                throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
            }
            // SCIM 에서 type 은 선택 필드다. 없으면 User 로 간주한다.
            boolean isGroup = member.type() != null && member.type().equalsIgnoreCase("Group");
            refs.add(isGroup ? MemberRef.group(member.value()) : MemberRef.user(member.value()));
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

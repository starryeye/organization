package dev.starryeye.organization.core.audit;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.List;
import java.util.Objects;

/**
 * 대상 하나(직원 또는 조직)가 사건 하나에서 겪은 변경 — 이력의 한 줄이다(설계 2026-10-10 §3.1·§3.3).
 *
 * @param subject        대상. 직원은 {@code MemberRef.user}, 조직은 {@code MemberRef.group}
 * @param attributes     바뀐 속성만, 이전 → 이후
 * @param joined         이 대상이 들어간 조직 id
 * @param left           이 대상이 나간 조직 id
 * @param membersAdded   조직 줄만 — 이 조직에 들어온 멤버
 * @param membersRemoved 조직 줄만 — 이 조직에서 나간 멤버
 * @param granted        이 대상이 user 쪽인 튜플 중 사건이 실제로 쓴 것
 * @param revoked        같은 것 중 사건이 실제로 지운 것
 */
public record AuditEntry(
        MemberRef subject,
        boolean created,
        boolean deleted,
        List<AttributeChange> attributes,
        List<String> joined,
        List<String> left,
        List<MemberRef> membersAdded,
        List<MemberRef> membersRemoved,
        List<RelationTuple> granted,
        List<RelationTuple> revoked
) {

    public AuditEntry {
        Objects.requireNonNull(subject, "subject");
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        joined = joined == null ? List.of() : List.copyOf(joined);
        left = left == null ? List.of() : List.copyOf(left);
        membersAdded = membersAdded == null ? List.of() : List.copyOf(membersAdded);
        membersRemoved = membersRemoved == null ? List.of() : List.copyOf(membersRemoved);
        granted = granted == null ? List.of() : List.copyOf(granted);
        revoked = revoked == null ? List.of() : List.copyOf(revoked);
    }

    /** 남길 것이 없다 — 이런 줄은 쓰지 않는다. */
    public boolean isEmpty() {
        return !created && !deleted && attributes.isEmpty() && joined.isEmpty() && left.isEmpty()
                && membersAdded.isEmpty() && membersRemoved.isEmpty() && granted.isEmpty() && revoked.isEmpty();
    }
}

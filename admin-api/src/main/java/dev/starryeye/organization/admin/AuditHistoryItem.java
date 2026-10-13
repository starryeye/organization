package dev.starryeye.organization.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditRecord;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.List;

/**
 * 변경 이력 한 줄의 응답(설계 2026-10-10 §5). 시각은 ISO-8601 UTC 문자열이고, 튜플은 {@code user:kim direct_member group:DEV} 꼴이다. 멤버
 * 들어옴·나감({@code membersAdded}·{@code membersRemoved})은 조직 줄에만 싣는다 — 직원 줄에는 그 필드가 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditHistoryItem(
        String at,
        Cause cause,
        boolean created,
        boolean deleted,
        List<AttributeChange> attributes,
        List<String> joined,
        List<String> left,
        List<String> granted,
        List<String> revoked,
        Members membersAdded,
        Members membersRemoved
) {

    /** 원인 — 회차면 {@code id} 가 {@code /admin/sync/runs} 의 runId, SCIM 요청이면 로그의 traceId 다. */
    public record Cause(String source, String kind, String id) {
    }

    public record Members(int count, List<Member> items) {
    }

    public record Member(String type, String id) {
    }

    static AuditHistoryItem of(AuditRecord record) {
        AuditEntry entry = record.entry();
        boolean 조직 = entry.subject().type() == MemberType.GROUP;
        return new AuditHistoryItem(
                record.at().toString(),
                new Cause(record.cause().source().name(), record.cause().kind().name(), record.cause().id()),
                entry.created(),
                entry.deleted(),
                entry.attributes(),
                entry.joined(),
                entry.left(),
                entry.granted().stream().map(AuditHistoryItem::튜플).toList(),
                entry.revoked().stream().map(AuditHistoryItem::튜플).toList(),
                조직 ? 멤버들(entry.membersAdded()) : null,
                조직 ? 멤버들(entry.membersRemoved()) : null);
    }

    private static Members 멤버들(List<MemberRef> refs) {
        return new Members(refs.size(), refs.stream().map(ref -> new Member(ref.type().name(), ref.id())).toList());
    }

    private static String 튜플(RelationTuple tuple) {
        return tuple.user() + " " + tuple.relation() + " " + tuple.object();
    }
}

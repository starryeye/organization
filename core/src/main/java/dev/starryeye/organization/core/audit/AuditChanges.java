package dev.starryeye.organization.core.audit;

import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.tuple.TupleSubjects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 사건 하나의 대상별 변경을 모은다(설계 2026-10-10 §3.3). 조직도 변경(직원·조직의 이전·이후, 멤버 넣고 빼기)과 권한 변경(쓴·지운 튜플)을 대상별로 묶어
 * {@link AuditEntry} 로 만든다.
 *
 * <ul>
 *   <li>조직의 멤버가 바뀌면 조직 줄에는 멤버 들어옴·나감을, 멤버 줄에는 소속 들어옴·나감을 함께 적는다.</li>
 *   <li>튜플은 user 쪽이 대상이다 — {@code user:kim direct_member group:DEV} 는 kim 의 줄, {@code group:TEAM child group:HQ} 는 TEAM 의 줄이다.</li>
 *   <li>한 대상의 이전·이후가 여러 번 오면 처음의 이전과 마지막의 이후를 비교한다.</li>
 *   <li>이름표가 그대로인 조직 결과는 멤버 변화만 적는다 — 상태(생성·삭제·속성)는 이름표가 바뀐 결과로만 본다(설계 2026-10-15 §5).</li>
 *   <li>빈 문자열은 없는 값으로 본다 — 저장소가 빈 값을 저장하지 않아, 그렇지 않으면 저장 전후 값이 늘 달라 보인다.</li>
 * </ul>
 *
 * <p>SCIM 요청 하나의 커밋 자리들이 동시에 더할 수 있어(직원 삭제의 소속 조직들) 메서드마다 잠근다.
 */
public final class AuditChanges {

    /** 속성 이름과 순서(설계 2026-10-10 §3.3). 조직은 앞의 둘 중 {@code externalId}·{@code displayName} 만 쓴다. */
    private static final List<String> 속성_순서 = List.of(
            "externalId", "userName", "displayName", "email", "active",
            "name.formatted", "name.familyName", "name.givenName", "name.middleName",
            "name.honorificPrefix", "name.honorificSuffix");

    private static final Comparator<MemberRef> 멤버순 =
            Comparator.comparing(MemberRef::type).thenComparing(MemberRef::id);
    private static final Comparator<RelationTuple> 튜플순 = Comparator.comparing(RelationTuple::user)
            .thenComparing(RelationTuple::relation)
            .thenComparing(RelationTuple::object);

    private final Map<MemberRef, 모음> 대상들 = new LinkedHashMap<>();

    /** 직원 하나의 이전·이후. 이전이 null 이면 생성, 이후가 null 이면 삭제다. */
    public synchronized AuditChanges user(DirectoryUser before, DirectoryUser after) {
        String id = after != null ? after.id() : before.id();
        대상(MemberRef.user(id)).상태를_본다(속성(before), before == null, 속성(after), after == null);
        return this;
    }

    /**
     * 조직 하나의 이름표 이전·이후와 들어오고 나간 멤버. 이름표가 null 인 쪽이 생성·삭제를 뜻한다.
     *
     * <p><b>이전과 이후의 이름표가 같으면 멤버 변화만 적고 상태는 보지 않는다(설계 2026-10-15 §5).</b> 변경을 끝나는 즉시 더하면 전체 교체가 GSI 밖
     * 조직에서 멤버를 뺄 때 내는 "멤버만 빠짐"(이름표를 모를 수 있다)이 그 조직의 삭제보다 먼저 온다. 상태를 보면 처음 본 이전을 붙들어 삭제 줄의 이름표가
     * 사라진다. 이름표가 같으면 속성 변화가 없으므로 한 번만 오는 경우의 결과는 전과 같다.
     */
    public synchronized AuditChanges group(GroupHeader before, GroupHeader after,
                                           Set<MemberRef> added, Set<MemberRef> removed) {
        String id = after != null ? after.id() : before.id();
        boolean 이름표가_그대로다 = before != null && after != null && 속성(before).equals(속성(after));
        if (!이름표가_그대로다) {
            대상(MemberRef.group(id)).상태를_본다(속성(before), before == null, 속성(after), after == null);
        }
        멤버를_적는다(id, added, removed);
        return this;
    }

    /** 이름표는 그대로이고 멤버만 바뀐 조직. */
    public synchronized AuditChanges membership(String groupId, Set<MemberRef> added, Set<MemberRef> removed) {
        멤버를_적는다(groupId, added, removed);
        return this;
    }

    /** 전체 교체의 비교 결과 전부. */
    public synchronized AuditChanges directory(DirectoryChanges changes) {
        changes.users().forEach(diff -> user(diff.before(), diff.after()));
        changes.groups().forEach(diff -> group(diff.before(), diff.after(), diff.added(), diff.removed()));
        return this;
    }

    /** 전체 교체가 변경마다 알리는 것을 이 모음에 더한다(설계 2026-10-15 §4). 회차가 도중에 실패해도 끝난 변경은 이미 들어 있다. */
    public DirectoryChanges.Listener directoryListener() {
        return new DirectoryChanges.Listener() {
            @Override
            public void user(DirectoryChanges.UserDiff diff) {
                AuditChanges.this.user(diff.before(), diff.after());
            }

            @Override
            public void group(DirectoryChanges.GroupDiff diff) {
                AuditChanges.this.group(diff.before(), diff.after(), diff.added(), diff.removed());
            }
        };
    }

    /** 사건이 OpenFGA 에 실제로 쓰고 지운 튜플. */
    public synchronized AuditChanges tuples(TupleWriteResult result) {
        result.written().forEach(tuple -> TupleSubjects.userSide(tuple).ifPresent(subject -> 대상(subject).granted.add(tuple)));
        return revoked(result.deleted());
    }

    /** 지운 튜플만 — 재적재·장부 맞추기는 쓴 줄을 남기지 않는다(설계 2026-10-10 §3.5). */
    public synchronized AuditChanges revoked(Set<RelationTuple> deleted) {
        deleted.forEach(tuple -> TupleSubjects.userSide(tuple).ifPresent(subject -> 대상(subject).revoked.add(tuple)));
        return this;
    }

    /** 남길 것이 있는 줄만, 대상이 처음 나온 순서로. */
    public synchronized List<AuditEntry> entries() {
        List<AuditEntry> entries = new ArrayList<>();
        대상들.forEach((subject, 모인것) -> {
            AuditEntry entry = 모인것.줄(subject);
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        });
        return entries;
    }

    private void 멤버를_적는다(String groupId, Set<MemberRef> added, Set<MemberRef> removed) {
        모음 조직 = 대상(MemberRef.group(groupId));
        조직.membersAdded.addAll(added);
        조직.membersRemoved.addAll(removed);
        added.forEach(member -> 대상(member).joined.add(groupId));
        removed.forEach(member -> 대상(member).left.add(groupId));
    }

    private 모음 대상(MemberRef subject) {
        return 대상들.computeIfAbsent(subject, ignored -> new 모음());
    }

    private static Map<String, String> 속성(DirectoryUser user) {
        Map<String, String> values = new LinkedHashMap<>();
        if (user == null) {
            return values;
        }
        넣는다(values, "externalId", user.externalId());
        넣는다(values, "userName", user.userName());
        넣는다(values, "displayName", user.displayName());
        넣는다(values, "email", user.email());
        넣는다(values, "active", String.valueOf(user.active()));
        PersonName name = user.name();
        넣는다(values, "name.formatted", name.formatted());
        넣는다(values, "name.familyName", name.familyName());
        넣는다(values, "name.givenName", name.givenName());
        넣는다(values, "name.middleName", name.middleName());
        넣는다(values, "name.honorificPrefix", name.honorificPrefix());
        넣는다(values, "name.honorificSuffix", name.honorificSuffix());
        return values;
    }

    private static Map<String, String> 속성(GroupHeader header) {
        Map<String, String> values = new LinkedHashMap<>();
        if (header == null) {
            return values;
        }
        넣는다(values, "externalId", header.externalId());
        넣는다(values, "displayName", header.displayName());
        return values;
    }

    private static void 넣는다(Map<String, String> values, String name, String value) {
        if (value != null && !value.isEmpty()) {
            values.put(name, value);
        }
    }

    /** 대상 하나에 모인 것. */
    private static final class 모음 {

        private boolean 상태를_봤다;
        private Map<String, String> 이전 = Map.of();
        private Map<String, String> 이후 = Map.of();
        private boolean 이전_없음;
        private boolean 이후_없음;
        private final Set<String> joined = new TreeSet<>();
        private final Set<String> left = new TreeSet<>();
        private final Set<MemberRef> membersAdded = new TreeSet<>(멤버순);
        private final Set<MemberRef> membersRemoved = new TreeSet<>(멤버순);
        private final Set<RelationTuple> granted = new TreeSet<>(튜플순);
        private final Set<RelationTuple> revoked = new TreeSet<>(튜플순);

        void 상태를_본다(Map<String, String> before, boolean beforeMissing,
                     Map<String, String> after, boolean afterMissing) {
            if (!상태를_봤다) {
                이전 = before;
                이전_없음 = beforeMissing;
                상태를_봤다 = true;
            }
            이후 = after;
            이후_없음 = afterMissing;
        }

        AuditEntry 줄(MemberRef subject) {
            boolean created = 상태를_봤다 && 이전_없음 && !이후_없음;
            boolean deleted = 상태를_봤다 && !이전_없음 && 이후_없음;
            List<AttributeChange> attributes = new ArrayList<>();
            for (String name : 속성_순서) {
                String before = 이전.get(name);
                String after = 이후.get(name);
                if (!Objects.equals(before, after)) {
                    attributes.add(new AttributeChange(name, before, after));
                }
            }
            return new AuditEntry(subject, created, deleted, attributes,
                    List.copyOf(joined), List.copyOf(left),
                    List.copyOf(membersAdded), List.copyOf(membersRemoved),
                    List.copyOf(granted), List.copyOf(revoked));
        }
    }
}

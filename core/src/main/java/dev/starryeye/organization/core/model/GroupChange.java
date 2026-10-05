package dev.starryeye.organization.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * SCIM 조직 PATCH·PUT 을 <b>저장소를 읽기 전에</b> 정리한 변경(조직 멤버 PATCH 설계 §4).
 *
 * <p>{@link #base()} 가 있으면 <b>전체 교체</b> — 그 목록에 {@link #ops()} 를 순서대로 적용한 것이 목표다. 없으면 <b>멤버 증분</b> —
 * 지금 멤버에 {@link #ops()} 를 순서대로 적용한다. 어느 쪽이든 뜻은 "멤버 집합에 연산을 순서대로 적용한다" 하나다({@link #replay}).
 * 멤버십을 보고 판단하는 일(지금 멤버인가, id 빼기가 직원·하위 조직 중 무엇인가)은 유스케이스가 락 안에서 한다.
 *
 * @param renames      이름을 바꾸는가. {@code displayName} 은 이것이 참일 때만 뜻이 있다(null 도 값이다)
 * @param reidentifies {@code externalId} 를 바꾸는가 — PUT, 그리고 {@code externalId} 를 다루는 PATCH 가 참이다
 * @param base         전체 교체의 시작 목록. 증분이면 null
 * @param ops          멤버 연산. 순서가 뜻이다
 */
public record GroupChange(boolean renames, String displayName,
                          boolean reidentifies, String externalId,
                          Set<MemberRef> base, List<MemberOp> ops) {

    public GroupChange {
        base = base == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(base));
        ops = List.copyOf(ops);
    }

    /** 멤버 연산 하나. */
    public sealed interface MemberOp permits Add, RemoveId {
    }

    /** 멤버를 넣는다. 이미 있으면 그대로다. */
    public record Add(MemberRef ref) implements MemberOp {
    }

    /**
     * id 로 뺀다 — {@code members[value eq "x"]} 에는 종류가 없다. 직원 x 와 하위 조직 x 가 둘 다 멤버면 한쪽만 뺀다({@link #replay}).
     */
    public record RemoveId(String id) implements MemberOp {
    }

    /** 아무것도 바꾸지 않는 증분. PATCH 의 연산을 여기서부터 쌓는다. */
    public static GroupChange delta() {
        return new GroupChange(false, null, false, null, null, List.of());
    }

    /** PUT — 이름·{@code externalId}·멤버를 통째로 바꾼다. */
    public static GroupChange replacement(String externalId, String displayName, Set<MemberRef> members) {
        return new GroupChange(true, displayName, true, externalId, members, List.of());
    }

    public GroupChange renamed(String newDisplayName) {
        return new GroupChange(true, newDisplayName, reidentifies, externalId, base, ops);
    }

    /**
     * {@code externalId} 를 바꾼다(null 이면 비운다). 유스케이스가 헤더의 값이 바뀌었을 때만 PUT 과 같은 중복 판정을 락 안에서 한다
     * (설계 2026-10-06 §4.1, RFC 7643 §3.1 readWrite).
     */
    public GroupChange reidentified(String newExternalId) {
        return new GroupChange(renames, displayName, true, newExternalId, base, ops);
    }

    /** 목표 목록을 정한다. 앞의 멤버 연산은 이 목록에 덮여 뜻이 없어진다. */
    public GroupChange replacing(Set<MemberRef> members) {
        return new GroupChange(renames, displayName, reidentifies, externalId, members, List.of());
    }

    public GroupChange adding(Set<MemberRef> refs) {
        List<MemberOp> next = new ArrayList<>(ops);
        refs.forEach(ref -> next.add(new Add(ref)));
        return new GroupChange(renames, displayName, reidentifies, externalId, base, next);
    }

    public GroupChange removingId(String id) {
        List<MemberOp> next = new ArrayList<>(ops);
        next.add(new RemoveId(id));
        return new GroupChange(renames, displayName, reidentifies, externalId, base, next);
    }

    public boolean replacesMembers() {
        return base != null;
    }

    /** 연산이 가리키는 멤버. id 빼기는 종류를 모르므로 직원·하위 조직 둘 다다. */
    public Set<MemberRef> mentioned() {
        Set<MemberRef> refs = new LinkedHashSet<>();
        for (MemberOp op : ops) {
            if (op instanceof Add add) {
                refs.add(add.ref());
            } else if (op instanceof RemoveId remove) {
                refs.add(MemberRef.user(remove.id()));
                refs.add(MemberRef.group(remove.id()));
            }
        }
        return refs;
    }

    /**
     * {@code start} 에 연산을 적용하는 동안 직원·하위 조직이 같은 id 로 <b>둘 다 멤버일 수 있는</b> 채 id 빼기를 만나는 id 들 —
     * 이때만 현재상태로 종류를 물어야 한다. 넣기만 따라가므로 실제보다 넉넉하게 센다.
     */
    public Set<String> ambiguousIds(Set<MemberRef> start) {
        Set<MemberRef> possible = new LinkedHashSet<>(start);
        Set<String> ids = new LinkedHashSet<>();
        for (MemberOp op : ops) {
            if (op instanceof Add add) {
                possible.add(add.ref());
            } else if (op instanceof RemoveId remove
                    && possible.contains(MemberRef.user(remove.id()))
                    && possible.contains(MemberRef.group(remove.id()))) {
                ids.add(remove.id());
            }
        }
        return ids;
    }

    /**
     * {@code start} 에 연산을 순서대로 적용한 멤버 집합. 직원·하위 조직이 같은 id 로 둘 다 멤버일 때 id 로 빼면
     * {@code 조직이면} 이 참이면 하위 조직을, 아니면 직원을 뺀다 — 전에 {@code StateMemberTypeResolver} 로 고르던 규칙과 같다.
     */
    public Set<MemberRef> replay(Set<MemberRef> start, Predicate<String> 조직이면) {
        Set<MemberRef> members = new LinkedHashSet<>(start);
        for (MemberOp op : ops) {
            if (op instanceof Add add) {
                members.add(add.ref());
            } else if (op instanceof RemoveId remove) {
                MemberRef user = MemberRef.user(remove.id());
                MemberRef group = MemberRef.group(remove.id());
                if (members.contains(user) && members.contains(group)) {
                    members.remove(조직이면.test(remove.id()) ? group : user);
                } else {
                    members.remove(user);
                    members.remove(group);
                }
            }
        }
        return members;
    }

    public GroupHeader applyTo(GroupHeader header) {
        return new GroupHeader(header.id(),
                reidentifies ? externalId : header.externalId(),
                renames ? displayName : header.displayName());
    }

    /**
     * 멤버 전체를 아는 경우의 결과. 전체 목록을 비교하던 방식과 같은 뜻을 정의하고, 테스트가 두 방식을 견주는 데 쓴다.
     */
    public DirectoryGroup applyTo(DirectoryGroup before, Predicate<String> 조직이면) {
        GroupHeader header = applyTo(new GroupHeader(before.id(), before.externalId(), before.displayName()));
        Set<MemberRef> start = replacesMembers() ? base : before.members();
        return new DirectoryGroup(header.id(), header.externalId(), header.displayName(), replay(start, 조직이면));
    }
}

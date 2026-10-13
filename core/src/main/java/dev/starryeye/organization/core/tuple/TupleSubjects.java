package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 튜플이 가리키는 대상(직원·조직)을 꺼낸다(설계 2026-10-13 §4.2). user 쪽은 {@code user:kim}(직원)·{@code group:TEAM}(조직), object 쪽은
 * {@code group:DEV}(조직)다. {@code #관계} 가 붙은 user 쪽(userset)과 다른 종류는 대상이 아니다.
 */
public final class TupleSubjects {

    private static final String 직원 = RelationTuple.USER_TYPE + ":";
    private static final String 조직 = RelationTuple.GROUP_TYPE + ":";

    private TupleSubjects() {
    }

    /** user 쪽 대상 — 이 튜플로 권한을 얻는 쪽이다. */
    public static Optional<MemberRef> userSide(RelationTuple tuple) {
        return 대상(tuple.user());
    }

    /** 튜플들이 가리키는 대상 전부 — user 쪽과 object 쪽을 함께, 처음 나온 순서로. */
    public static Set<MemberRef> of(Collection<RelationTuple> tuples) {
        Set<MemberRef> subjects = new LinkedHashSet<>();
        for (RelationTuple tuple : tuples) {
            대상(tuple.user()).ifPresent(subjects::add);
            대상(tuple.object()).ifPresent(subjects::add);
        }
        return subjects;
    }

    private static Optional<MemberRef> 대상(String typedId) {
        if (typedId.contains("#")) {
            return Optional.empty();
        }
        if (typedId.startsWith(직원)) {
            return Optional.of(MemberRef.user(typedId.substring(직원.length())));
        }
        if (typedId.startsWith(조직)) {
            return Optional.of(MemberRef.group(typedId.substring(조직.length())));
        }
        return Optional.empty();
    }
}

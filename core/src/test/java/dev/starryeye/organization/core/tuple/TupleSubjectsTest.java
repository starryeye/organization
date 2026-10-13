package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TupleSubjectsTest {

    @Test
    @DisplayName("user 쪽 대상 — user:kim 은 직원, group:TEAM 은 조직. #관계가 붙거나 다른 종류면 대상이 아니다")
    void user_쪽_대상() {
        // given, when, then
        assertThat(TupleSubjects.userSide(RelationTuple.directMember("kim", "DEV"))).contains(MemberRef.user("kim"));
        assertThat(TupleSubjects.userSide(RelationTuple.child("TEAM", "DEV"))).contains(MemberRef.group("TEAM"));
        assertThat(TupleSubjects.userSide(new RelationTuple("group:DEV#member", "viewer", "doc:1"))).isEmpty();
        assertThat(TupleSubjects.userSide(new RelationTuple("doc:1", "parent", "group:DEV"))).isEmpty();
    }

    @Test
    @DisplayName("튜플들이 가리키는 대상 전부 — user 쪽과 object 쪽을 함께")
    void 양쪽_대상() {
        // given
        List<RelationTuple> tuples = List.of(RelationTuple.directMember("kim", "DEV"), RelationTuple.child("TEAM", "HQ"));

        // when, then
        assertThat(TupleSubjects.of(tuples)).containsExactlyInAnyOrder(
                MemberRef.user("kim"), MemberRef.group("DEV"), MemberRef.group("TEAM"), MemberRef.group("HQ"));
    }
}

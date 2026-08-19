package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFgaRelationTupleCheckerTest extends OpenFgaTestSupport {

    private OpenFgaRelationTupleWriter writer;
    private OpenFgaRelationTupleChecker checker;

    @BeforeEach
    void 어댑터를_준비한다() {
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
        checker = new OpenFgaRelationTupleChecker(bootstrapper);
    }

    @Test
    @DisplayName("쓴 튜플은 Check 가 true 로 답한다")
    void 쓴_튜플은_true다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:kim", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("하위 조직을 통한 롤업도 한 번의 Check 로 true 가 된다")
    void 롤업도_한_번의_Check로_답한다() {
        // given — DEV001 ⊇ DEV002 ⊇ kim
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:kim", "member", "group:DEV001")).block();

        // then — member 는 direct_member or member from child 로 정의돼 있다
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("없는 튜플은 false 다")
    void 없는_튜플은_false다() {
        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:nobody", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("상속은 상위로만 향한다 — 상위 직속은 하위의 멤버가 아니다")
    void 상속은_상위로만_향한다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("park", "DEV001"),
                RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:park", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isFalse();
    }
}

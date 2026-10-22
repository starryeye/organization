package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TupleWriteResultTest {

    @Test
    @DisplayName("불확실 실패만 불확실 줄로 꺼낸다 — 확정 실패는 들어가지 않는다")
    void 불확실_실패만_불확실_줄로_꺼낸다() {
        // given
        RelationTuple a = RelationTuple.directMember("kim", "DEV001");
        RelationTuple b = RelationTuple.directMember("lee", "DEV001");
        TupleWriteResult 결과 = new TupleWriteResult(Set.of(), Set.of(), List.of(
                new TupleFailure(a, "거절"),
                TupleFailure.uncertain(b, "연결 거부")));

        // when
        Set<RelationTuple> 불확실 = 결과.uncertain();

        // then
        assertThat(불확실).containsExactly(b);
    }

    @Test
    @DisplayName("두 인자 생성자로 만든 실패는 확정이다 — 이미 있는 호출자는 그대로 확정 실패를 낸다")
    void 두_인자_생성자는_확정_실패다() {
        // when
        TupleFailure 실패 = new TupleFailure(RelationTuple.directMember("kim", "DEV001"), "거절");

        // then
        assertThat(실패.uncertain()).isFalse();
        assertThat(TupleFailure.uncertain(실패.tuple(), "연결 거부").uncertain()).isTrue();
    }

    @Test
    @DisplayName("실패가 없으면 불확실 줄도 없다")
    void 실패가_없으면_불확실_줄도_없다() {
        // expect
        assertThat(TupleWriteResult.empty().uncertain()).isEmpty();
    }
}

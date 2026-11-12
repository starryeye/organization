package dev.starryeye.organization.core.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnsourcedGrantTrackerTest {

    private static final RelationTuple A = RelationTuple.directMember("a", "G");
    private static final RelationTuple B = RelationTuple.directMember("b", "G");
    private static final RelationTuple C = RelationTuple.directMember("c", "G");
    private static final RelationTuple E = RelationTuple.directMember("e", "G");

    private final AtomicInteger 셈 = new AtomicInteger();
    private final UnsourcedGrantTracker 추적 = UnsourcedGrantTracker.조직("G", 셈::incrementAndGet);

    private static TupleWriteResult 쓴(Set<RelationTuple> written) {
        return new TupleWriteResult(written, Set.of(), List.of());
    }

    private static TupleWriteResult 실패(List<TupleFailure> failures) {
        return new TupleWriteResult(Set.of(), Set.of(), failures);
    }

    @Test
    @DisplayName("실패가 없으면 되지울 줄이 없다")
    void 실패가_없으면_되지울_줄이_없다() {
        // given
        추적.요구한다(Set.of());

        // when
        Set<RelationTuple> 되지울 = 추적.되지울것(쓴(Set.of(A)));

        // then
        assertThat(되지울).isEmpty();
    }

    @Test
    @DisplayName("저장하지 않는 커밋은 있어야 했던 것을 뺀 쓴 줄과 결과를 모르는 줄을 되지운다")
    void 저장하지_않는_커밋은_있어야했던것을_뺀_쓴_줄과_결과를_모르는_줄을_되지운다() {
        // given
        추적.요구한다(Set.of(B));
        TupleWriteResult 결과 = new TupleWriteResult(Set.of(A), Set.of(),
                List.of(TupleFailure.uncertain(B, "x"), TupleFailure.uncertain(C, "x"), new TupleFailure(E, "x")));

        // when
        Set<RelationTuple> 되지울 = 추적.되지울것(결과);

        // then
        assertThat(되지울).containsExactlyInAnyOrder(A, C);
    }

    @Test
    @DisplayName("저장한 뒤에는 저장하지 못한 넣기의 줄만 되지운다")
    void 저장한_뒤에는_저장하지_못한_넣기의_줄만_되지운다() {
        // given
        추적.요구한다(Set.of());
        추적.저장했다(Set.of(C));
        TupleWriteResult 결과 = new TupleWriteResult(Set.of(A), Set.of(),
                List.of(TupleFailure.uncertain(C, "x"), TupleFailure.uncertain(E, "x")));

        // when
        Set<RelationTuple> 되지울 = 추적.되지울것(결과);

        // then
        assertThat(되지울).containsExactly(C);
    }

    @Test
    @DisplayName("오류로 끝나면 요구하지 않는 보낸 줄이 있을 때 한 번 센다")
    void 오류로_끝나면_요구하지_않는_보낸_줄이_있을_때_한_번_센다() {
        // given
        추적.요구한다(Set.of(A));
        List<TupleWriteResult> 받은 = new ArrayList<>();
        TupleWriteResult 결과 = 쓴(Set.of(A, B));

        // when
        Consumer<TupleWriteResult> 묶음 = 추적.묶음마다(TupleDelta.writeOnly(Set.of(A, B)), 받은::add);
        묶음.accept(결과);
        추적.쓰기가_끝났다();
        추적.오류로_끝났다(new IllegalStateException("boom"));
        추적.오류로_끝났다(new IllegalStateException("boom"));

        // then
        assertThat(셈.get()).isEqualTo(1);
        assertThat(받은).containsExactly(결과);
    }

    @Test
    @DisplayName("요구하는 줄만 보냈으면 세지 않는다")
    void 요구하는_줄만_보냈으면_세지_않는다() {
        // given
        추적.요구한다(Set.of(A));

        // when
        추적.묶음마다(TupleDelta.writeOnly(Set.of(A)), r -> { }).accept(쓴(Set.of(A)));
        추적.쓰기가_끝났다();
        추적.오류로_끝났다(new IllegalStateException("boom"));

        // then
        assertThat(셈.get()).isZero();
    }

    @Test
    @DisplayName("확정 실패만 알려 왔으면 세지 않는다")
    void 확정_실패만_알려_왔으면_세지_않는다() {
        // given
        추적.요구한다(Set.of());

        // when
        추적.묶음마다(TupleDelta.writeOnly(Set.of(A)), r -> { })
                .accept(실패(List.of(new TupleFailure(A, "x"))));
        추적.쓰기가_끝났다();
        추적.오류로_끝났다(new IllegalStateException("boom"));

        // then
        assertThat(셈.get()).isZero();
    }

    @Test
    @DisplayName("쓰는 도중 취소되면 알려 오지 않은 쓰기 줄도 센다")
    void 쓰는_도중_취소되면_알려_오지_않은_쓰기_줄도_센다() {
        // given
        추적.요구한다(Set.of());
        추적.묶음마다(TupleDelta.writeOnly(Set.of(A)), r -> { });

        // when
        추적.취소됐다();

        // then
        assertThat(셈.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("쓰기가 끝난 뒤의 오류는 알려 온 줄만 본다")
    void 쓰기가_끝난_뒤의_오류는_알려_온_줄만_본다() {
        // given
        추적.요구한다(Set.of());

        // when
        추적.묶음마다(TupleDelta.writeOnly(Set.of(A, B)), r -> { })
                .accept(실패(List.of(new TupleFailure(A, "x"))));
        추적.쓰기가_끝났다();
        추적.오류로_끝났다(new IllegalStateException("boom"));

        // then
        assertThat(셈.get()).isZero();
    }

    @Test
    @DisplayName("커밋을 마친 뒤에는 세지 않는다")
    void 커밋을_마친_뒤에는_세지_않는다() {
        // given
        추적.요구한다(Set.of());

        // when
        추적.묶음마다(TupleDelta.writeOnly(Set.of(A)), r -> { }).accept(쓴(Set.of(A)));
        추적.쓰기가_끝났다();
        추적.커밋을_마쳤다();
        추적.오류로_끝났다(new IllegalStateException("boom"));
        추적.취소됐다();

        // then
        assertThat(셈.get()).isZero();
    }

    @Test
    @DisplayName("되지우기 실패와 커밋 전 끝남은 합쳐 한 번이다")
    void 되지우기_실패와_커밋_전_끝남은_합쳐_한_번이다() {
        // given
        추적.요구한다(Set.of());

        // when
        추적.묶음마다(TupleDelta.writeOnly(Set.of(A)), r -> { }).accept(쓴(Set.of(A)));
        추적.쓰기가_끝났다();
        추적.되지우기가_실패했다();
        추적.오류로_끝났다(new IllegalStateException("boom"));

        // then
        assertThat(셈.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("결과를 모르는 지우기는 보냈을 수 있는 부여 줄이 아니다")
    void 결과를_모르는_지우기는_보냈을_수_있는_부여_줄이_아니다() {
        // given
        추적.요구한다(Set.of());

        // when
        추적.묶음마다(TupleDelta.deleteOnly(Set.of(A)), r -> { })
                .accept(실패(List.of(TupleFailure.uncertain(A, "x"))));
        추적.쓰기가_끝났다();
        추적.오류로_끝났다(new IllegalStateException("boom"));

        // then
        assertThat(셈.get()).isZero();
    }

    @Test
    @DisplayName("관찰자가 던져도 삼킨다")
    void 관찰자가_던져도_삼킨다() {
        // given
        UnsourcedGrantTracker 던지는 = UnsourcedGrantTracker.조직("G", () -> {
            throw new IllegalStateException("meter down");
        });
        던지는.요구한다(Set.of());
        던지는.묶음마다(TupleDelta.writeOnly(Set.of(A)), r -> { }).accept(쓴(Set.of(A)));

        // when / then
        assertThatCode(() -> 던지는.오류로_끝났다(new IllegalStateException("boom"))).doesNotThrowAnyException();
    }
}

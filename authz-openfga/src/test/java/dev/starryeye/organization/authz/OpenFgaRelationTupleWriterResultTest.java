package dev.starryeye.organization.authz;

import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.ResultAccumulator;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 묶음 결과를 누적기 하나에 모은다 — 묶음마다 누적 전체를 복사하면 비용이 묶음 수의 제곱이다(점검 P6, 설계 2026-10-07 §5). */
class OpenFgaRelationTupleWriterResultTest {

    private static List<Batch> 배치들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.directMember("user" + i, "DEV002"))))
                .toList();
    }

    @Test
    @DisplayName("누적기는 쓴 줄·지운 줄을 합집합으로, 실패는 더한 순서대로 모으고 결과는 불변이다")
    void 누적기는_합치고_순서를_지킨다() {
        // given
        RelationTuple 가 = RelationTuple.directMember("kim", "DEV001");
        RelationTuple 나 = RelationTuple.directMember("lee", "DEV001");
        RelationTuple 다 = RelationTuple.directMember("park", "DEV001");
        ResultAccumulator 누적기 = new ResultAccumulator();

        // when
        누적기.더한다(new TupleWriteResult(Set.of(가), Set.of(), List.of(new TupleFailure(다, "첫 실패"))));
        누적기.더한다(new TupleWriteResult(Set.of(가, 나), Set.of(다), List.of(new TupleFailure(나, "둘째 실패"))));
        TupleWriteResult 결과 = 누적기.결과();

        // then
        assertThat(결과.written()).containsExactlyInAnyOrder(가, 나);
        assertThat(결과.deleted()).containsExactly(다);
        assertThat(결과.failures()).extracting(TupleFailure::reason).containsExactly("첫 실패", "둘째 실패");
        assertThatThrownBy(() -> 결과.written().add(다)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("묶음이 2,000개여도 결과를 빠짐없이 모은다")
    void 묶음이_많아도_빠짐없이_모은다() {
        // given
        Function<Batch, Mono<TupleWriteResult>> 늘_성공 = batch -> Mono.just(batch.succeeded());

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(2_000), 늘_성공).block();

        // then
        assertThat(결과.written()).hasSize(2_000)
                .contains(RelationTuple.directMember("user0", "DEV002"), RelationTuple.directMember("user1999", "DEV002"));
        assertThat(결과.failures()).isEmpty();
    }

    @Test
    @DisplayName("띄엄띄엄 실패한 묶음은 보낸 순서대로 실패에 담고 나머지는 쓴 줄에 담는다")
    void 실패는_보낸_순서대로다() {
        // given — 3번과 7번 묶음만 실패한다(연달아가 아니라 차단기는 서지 않는다)
        Function<Batch, Mono<TupleWriteResult>> 둘만_실패 = batch -> {
            String user = batch.tuples().get(0).user();
            return Mono.just(user.equals("user:user3") || user.equals("user:user7") ? batch.failed("거절") : batch.succeeded());
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(10), 둘만_실패).block();

        // then
        assertThat(결과.written()).hasSize(8);
        assertThat(결과.failures()).extracting(failure -> failure.tuple().user()).containsExactly("user:user3", "user:user7");
    }
}

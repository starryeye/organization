package dev.starryeye.organization.authz;

import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenFGA 가 죽은 채로 배치 1,100개를 하나하나 재시도하며 락을 수십 분 쥐지 않는다 (점검 C7, 설계 2026-09-29 §5).
 */
class OpenFgaRelationTupleWriterBreakerTest {

    private static List<Batch> 배치들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.directMember("user" + i, "DEV002"))))
                .toList();
    }

    @Test
    @DisplayName("배치가 3번 연달아 실패하면 남은 배치를 보내지 않고 오류로 끝난다")
    void 세번_연달아_실패하면_멈춘다() {
        // given
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> 늘_실패 = batch -> {
            보낸것.add(batch);
            return Mono.just(batch.failed("연결 거부"));
        };

        // when, then
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(10), 늘_실패).block())
                .hasMessageContaining("3번 연달아")
                .hasMessageContaining("남은 7개")
                .hasMessageContaining("연결 거부");
        assertThat(보낸것).hasSize(3);
    }

    @Test
    @DisplayName("실패가 연달아 셋이 아니면 끝까지 보내고 실패는 결과에 담는다 — 드문 실패는 지금처럼 PARTIAL 로 넘긴다")
    void 연달아_셋이_아니면_끝까지_보낸다() {
        // given — 실패, 실패, 성공이 되풀이된다. 성공이 끼면 연속 횟수는 처음부터 다시 센다
        AtomicInteger 차례 = new AtomicInteger();
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> 셋째마다_성공 = batch -> {
            보낸것.add(batch);
            return Mono.just(차례.incrementAndGet() % 3 == 0 ? batch.succeeded() : batch.failed("일시 오류"));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(9), 셋째마다_성공).block();

        // then
        assertThat(보낸것).hasSize(9);
        assertThat(결과.failures()).hasSize(6);
        assertThat(결과.written()).hasSize(3);
    }

    @Test
    @DisplayName("배치가 셋보다 적으면 모두 실패해도 오류가 아니라 결과의 실패로 남는다 — SCIM 요청 한 건")
    void 배치가_적으면_차단기와_무관하다() {
        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(
                배치들(2), batch -> Mono.just(batch.failed("x"))).block();

        // then
        assertThat(결과.failures()).hasSize(2);
    }
}

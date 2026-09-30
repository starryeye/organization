package dev.starryeye.organization.authz;

import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잘못된 줄 하나가 같은 배치 100줄을 끌고 가지 않는다 (점검 M16, 설계 2026-09-30 §6.2).
 */
class OpenFgaRelationTupleWriterSplitTest {

    /** 테스트용 거절 — OpenFGA 400 을 흉내 낸다. */
    private static final class 거절 extends RuntimeException {
        거절(String message) {
            super(message);
        }
    }

    private static final Predicate<Throwable> 거절인가 = error -> error instanceof 거절;

    private static List<RelationTuple> 줄들(int count) {
        return IntStream.range(0, count).mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002")).toList();
    }

    @Test
    @DisplayName("나쁜 줄 하나가 섞인 배치는 반씩 쪼개 나머지를 반영하고 그 줄만 실패로 남긴다")
    void 나쁜_줄만_실패로_남긴다() {
        // given — 8줄 중 하나가 거절된다
        List<RelationTuple> 줄 = 줄들(8);
        RelationTuple 나쁜_줄 = 줄.get(5);
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        Function<Batch, Mono<Void>> send = batch -> {
            보낸_횟수.incrementAndGet();
            return batch.tuples().contains(나쁜_줄) ? Mono.error(new 거절("없는 타입")) : Mono.empty();
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.쪼개며_보낸다(Batch.writes(줄), send, 거절인가).block();

        // then — 8 → 4 → 2 → 1 로 좁혀 간다: 처음 1번 + 층마다 2번씩 세 층
        assertThat(결과.written()).hasSize(7).doesNotContain(나쁜_줄);
        assertThat(결과.failures()).extracting(TupleFailure::tuple).containsExactly(나쁜_줄);
        assertThat(결과.failures().get(0).reason()).contains("없는 타입");
        assertThat(보낸_횟수).hasValue(7);
    }

    @Test
    @DisplayName("일시 오류는 쪼개지 않는다 — 쪼개도 같이 실패한다")
    void 일시_오류는_쪼개지_않는다() {
        // given
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        Function<Batch, Mono<Void>> send = batch -> {
            보낸_횟수.incrementAndGet();
            return Mono.error(new IllegalStateException("연결 거부"));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.쪼개며_보낸다(Batch.writes(줄들(8)), send, 거절인가).block();

        // then
        assertThat(보낸_횟수).hasValue(1);
        assertThat(결과.failures()).hasSize(8);
        assertThat(결과.written()).isEmpty();
    }

    @Test
    @DisplayName("다 거절이면(인가 모델이 없음) 쪼개기가 폭주하지 않는다 — 배치 n줄에 호출 2n−1 번")
    void 다_거절이면_호출이_묶인다() {
        // given
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        Function<Batch, Mono<Void>> send = batch -> {
            보낸_횟수.incrementAndGet();
            return Mono.error(new 거절("모델 없음"));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.쪼개며_보낸다(Batch.writes(줄들(8)), send, 거절인가).block();

        // then
        assertThat(보낸_횟수).hasValue(15);
        assertThat(결과.failures()).hasSize(8);
        assertThat(결과.written()).isEmpty();
    }
}

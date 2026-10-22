package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 쓰기 어댑터의 "서버는 반영했는데 응답을 잃음" 흉내가 진짜 불확실 실패의 모양을 내는지 확인한다.
 * 회수 수렴 시험이 이것에 기대므로 여기가 틀리면 그 위의 시험이 헛돈다.
 */
class FakeTupleWriterTest {

    private static final RelationTuple 가 = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple 나 = RelationTuple.directMember("lee", "DEV001");
    private static final RelationTuple 다 = RelationTuple.directMember("park", "DEV001");

    @Test
    @DisplayName("응답을 잃은 쓰기는 장부에는 반영하고 결과에는 불확실 실패로 넣는다")
    void 응답을_잃은_쓰기는_장부에만_반영된다() {
        // given
        var writer = new FakeTupleWriter();
        writer.lostResponseFor(가::equals);

        // when
        TupleWriteResult 결과 = writer.apply(TupleDelta.writeOnly(Set.of(가, 나))).block();

        // then — 가짜 장부와 기록에는 들어갔지만, 결과의 쓴 줄에는 없고 불확실 실패로 있다
        assertThat(writer.stored).containsExactlyInAnyOrder(가, 나);
        assertThat(writer.written).containsExactlyInAnyOrder(가, 나);
        assertThat(결과.written()).containsExactly(나);
        assertThat(결과.failures()).singleElement().satisfies(실패 -> {
            assertThat(실패.tuple()).isEqualTo(가);
            assertThat(실패.reason()).isEqualTo("테스트용 응답 유실");
            assertThat(실패.uncertain()).isTrue();
        });
        assertThat(결과.uncertain()).containsExactly(가);
    }

    @Test
    @DisplayName("응답을 잃은 지우기는 장부에서 지우고 결과에는 지운 줄이 아닌 불확실 실패로 넣는다")
    void 응답을_잃은_지우기는_장부에서_지워진다() {
        // given
        var writer = new FakeTupleWriter();
        writer.stored.addAll(Set.of(가, 나));
        writer.lostResponseFor(가::equals);

        // when
        TupleWriteResult 결과 = writer.apply(TupleDelta.deleteOnly(Set.of(가, 나))).block();

        // then
        assertThat(writer.stored).isEmpty();
        assertThat(writer.deleted).containsExactlyInAnyOrder(가, 나);
        assertThat(결과.deleted()).containsExactly(나);
        assertThat(결과.uncertain()).containsExactly(가);
    }

    @Test
    @DisplayName("응답 유실과 실패가 겹치면 실패가 이긴다 — 장부에 반영하지 않고 확정 실패로 낸다")
    void 겹치면_실패가_이긴다() {
        // given
        var writer = new FakeTupleWriter();
        writer.lostResponseFor(줄 -> true);
        writer.failFor(다::equals);

        // when
        TupleWriteResult 결과 = writer.apply(TupleDelta.writeOnly(Set.of(가, 다))).block();

        // then
        assertThat(writer.stored).containsExactly(가);
        assertThat(결과.failures()).hasSize(2);
        assertThat(결과.failures()).filteredOn(실패 -> 실패.tuple().equals(다))
                .singleElement()
                .satisfies(실패 -> {
                    assertThat(실패.reason()).isEqualTo("테스트용 실패");
                    assertThat(실패.uncertain()).isFalse();
                });
        assertThat(결과.uncertain()).containsExactly(가);
    }

    @Test
    @DisplayName("응답 유실도 묶음마다 알림에 같은 결과로 나간다")
    void 응답_유실도_묶음마다_알린다() {
        // given
        var writer = new FakeTupleWriter();
        writer.lostResponseFor(가::equals);
        List<TupleWriteResult> 알린것 = new CopyOnWriteArrayList<>();

        // when
        TupleWriteResult 결과 = writer.apply(TupleDelta.writeOnly(Set.of(가)), 알린것::add).block();

        // then
        assertThat(알린것).containsExactly(결과);
        assertThat(결과.failures()).extracting(TupleFailure::uncertain).containsExactly(true);
    }
}

package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BatchCheck 로 "OpenFGA 에 실제로 있는 튜플" 을 읽어온다 (설계 §5.3).
 *
 * <p>열거 API 를 쓰지 않고도 <b>후보를 알고 있다면</b> 실제 상태를 알 수 있다는 것이 요점이다.
 */
class OpenFgaBatchCheckTest extends OpenFgaTestSupport {

    private OpenFgaRelationTupleWriter writer;
    private OpenFgaRelationTupleChecker checker;

    @BeforeEach
    void 어댑터를_준비한다() {
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
        checker = new OpenFgaRelationTupleChecker(bootstrapper);
    }

    private void 튜플을_심는다(List<RelationTuple> tuples) {
        writer.apply(TupleDelta.writeOnly(Set.copyOf(tuples))).block();
    }

    @Test
    @DisplayName("후보 중 실제로 있는 튜플만 돌려준다")
    void 있는_것만_돌려준다() {
        // given — kim 만 심고 park 는 심지 않는다
        var 있는것 = RelationTuple.directMember("kim", "DEV001");
        var 없는것 = RelationTuple.directMember("park", "DEV001");
        튜플을_심는다(List.of(있는것));

        // when
        Set<RelationTuple> 실제 = checker.existing(Set.of(있는것, 없는것)).block();

        // then
        assertThat(실제).containsExactly(있는것);
    }

    @Test
    @DisplayName("후보가 비면 OpenFGA 를 부르지 않고 빈 집합을 준다")
    void 후보가_비면_빈_집합이다() {
        // when
        Set<RelationTuple> 실제 = checker.existing(Set.of()).block();

        // then
        assertThat(실제).isEmpty();
    }

    @Test
    @DisplayName("배치 상한(50)을 넘는 후보도 나눠 물어 전부 확인한다")
    void 상한을_넘으면_나눠_묻는다() {
        // given — 120명 중 짝수 번째만 심는다. 청크 경계에서 빠뜨리면 여기서 드러난다.
        List<RelationTuple> 전체 = IntStream.range(0, 120)
                .mapToObj(i -> RelationTuple.directMember("user%03d".formatted(i), "DEV001"))
                .toList();
        List<RelationTuple> 심을것 = IntStream.range(0, 120)
                .filter(i -> i % 2 == 0)
                .mapToObj(전체::get)
                .toList();
        튜플을_심는다(심을것);

        // when
        Set<RelationTuple> 실제 = checker.existing(Set.copyOf(전체)).block();

        // then
        assertThat(실제).hasSize(60);
        assertThat(실제).containsExactlyInAnyOrderElementsOf(심을것);
    }

    @Test
    @DisplayName("child 관계도 같은 방식으로 확인된다")
    void child도_확인된다() {
        // given
        var 있는것 = RelationTuple.child("DEV002", "DEV001");
        var 없는것 = RelationTuple.child("DEV003", "DEV001");
        튜플을_심는다(List.of(있는것));

        // when
        Set<RelationTuple> 실제 = checker.existing(Set.of(있는것, 없는것)).block();

        // then
        assertThat(실제).containsExactly(있는것);
    }
}

package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장부 청소 — 재적재의 2~3단계 (설계 2026-09-29 §3.1). 순서·기록 규칙은 두 재적재 유스케이스 테스트가 본다.
 */
class TupleReconcilerTest {

    @Test
    @DisplayName("구독하기 전에는 장부에 아무것도 하지 않는다 — 부르기만 하고 구독하지 않은 청소가 장부를 건드리면 안 된다")
    void 구독하기_전에는_쓰지_않는다() {
        // given
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);

        // when
        TupleReconciler.reconcile(writer, scanner, Set.of(RelationTuple.directMember("kim", "DEV002")));

        // then
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(scanner.scanCount).hasValue(0);
    }

    @Test
    @DisplayName("지우기 전 확인이 멈출 이유를 주면 쓴 줄은 두고 아무것도 지우지 않는다")
    void 멈출_이유가_있으면_지우지_않는다() {
        // given
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.add(찌꺼기);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김),
                (desired, stale, scanned) -> Optional.of("멈춤(테스트)")).block();

        // then
        assertThat(reconciliation.held()).isTrue();
        assertThat(reconciliation.heldReason()).isEqualTo("멈춤(테스트)");
        assertThat(writer.stored).containsExactlyInAnyOrder(김, 찌꺼기);
        assertThat(writer.deleted).isEmpty();
    }
}

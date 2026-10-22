package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Test
    @DisplayName("이미 있던 줄의 다시 쓰기가 실패해도 장부에 남는다")
    void 이미_있던_줄의_다시_쓰기가_실패해도_장부에_남는다() {
        // given — 김은 이미 장부에 있고, 이는 장부에 없다. 둘 다 다시 쓰기가 실패한다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        writer.stored.add(김);
        writer.failFor(tuple -> tuple.equals(김) || tuple.equals(이));

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김, 이)).block();

        // then — 장부에 있던 김은 다시 쓰기가 실패해도 스냅샷에 남는다. 장부에 없던 이는 남지 않는다
        assertThat(reconciliation.ledger()).containsExactly(김);
        assertThat(reconciliation.result().failures())
                .extracting(TupleFailure::tuple)
                .containsExactlyInAnyOrder(김, 이);
    }

    @Test
    @DisplayName("다시 정해 더한 줄 중 장부에 있는 것은 지우지 않고, 장부에 없는 것은 쓴다")
    void 더한_줄은_지우지_않고_없으면_쓴다() {
        // given — 장부에 kim(D), lee(D 밖), ghost(D 밖). 다시 정하기가 lee 와 park 을 더한다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        RelationTuple 유령 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.addAll(Set.of(김, 이, 유령));

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김),
                (desired, stale) -> Mono.just(Set.of(김, 이, 박)), TupleReconciler.DeleteCheck.NONE).block();

        // then
        assertThat(writer.deleted).containsExactly(유령);
        assertThat(writer.stored).containsExactlyInAnyOrder(김, 이, 박);
        assertThat(reconciliation.result().written()).contains(박);
        assertThat(reconciliation.ledger()).containsExactlyInAnyOrder(김, 이, 박);
    }

    @Test
    @DisplayName("빈 조직도 확인은 다시 정한 줄로 본다 — 처음 D 가 비었어도 더한 줄이 있으면 멈추지 않는다")
    void 빈_조직도_확인은_다시_정한_줄로_본다() {
        // given — 처음 D 는 비었고 장부에 lee 가 있다. 다시 정하기가 lee 를 더한다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        writer.stored.add(이);
        TupleReconciler.DeleteCheck 빈_조직도면_멈춘다 = (desired, stale, scanned) ->
                desired.isEmpty() && !stale.isEmpty() ? Optional.of("빈 조직도") : Optional.empty();

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(),
                (desired, stale) -> Mono.just(Set.of(이)), 빈_조직도면_멈춘다).block();

        // then
        assertThat(reconciliation.held()).isFalse();
        assertThat(writer.deleted).isEmpty();
        assertThat(writer.stored).containsExactly(이);
    }

    @Test
    @DisplayName("다시 정해 더한 줄의 쓰기가 실패하면 장부에 있다고 보지 않고 실패로 남긴다")
    void 더한_줄의_쓰기가_실패하면_장부에_남지_않는다() {
        // given — D 는 kim 하나. 다시 정하기가 park 을 더하는데 park 의 쓰기가 실패한다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        writer.failFor(tuple -> tuple.equals(박));

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김),
                (desired, stale) -> Mono.just(Set.of(김, 박)), TupleReconciler.DeleteCheck.NONE).block();

        // then
        assertThat(reconciliation.ledger()).containsExactly(김);
        assertThat(reconciliation.result().failures()).extracting(TupleFailure::tuple).containsExactly(박);
        assertThat(writer.stored).containsExactly(김);
    }

    @Test
    @DisplayName("다시 정한 D' 에 없는 줄은 D 에 있었어도 지운다 — 장부에 있던 줄이 지울 후보로 넘어가고 장부 목록에서 빠진다")
    void 다시_정하며_빠진_줄은_지운다() {
        // given — D 는 kim·lee, 장부에도 둘 다 있다. 다시 정한 D' 는 kim 하나다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김, 이),
                (desired, stale) -> Mono.just(Set.of(김)), TupleReconciler.DeleteCheck.NONE).block();

        // then
        assertThat(writer.deleted).containsExactly(이);
        assertThat(writer.stored).containsExactly(김);
        assertThat(reconciliation.result().deleted()).containsExactly(이);
        assertThat(reconciliation.ledger()).containsExactly(김);
    }

    @Test
    @DisplayName("다시 정하며 빠진 줄이 애초에 장부에 없었으면(처음 쓰기가 실패했다) 지울 후보에도 장부 목록에도 넣지 않는다")
    void 다시_정하며_빠진_줄이_장부에_없었으면_지우지_않는다() {
        // given — D 는 kim·lee, lee 의 처음 쓰기가 실패해 장부에 없다. 다시 정한 D' 는 kim 하나다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        writer.failFor(tuple -> tuple.equals(이));

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김, 이),
                (desired, stale) -> Mono.just(Set.of(김)), TupleReconciler.DeleteCheck.NONE).block();

        // then
        assertThat(writer.deleted).isEmpty();
        assertThat(reconciliation.ledger()).containsExactly(김);
    }

    @Test
    @DisplayName("지우기 전 확인은 다시 정한 D' 와 넘어간 후보를 본다 — D' 가 비면 D 의 줄도 후보라 멈춘다")
    void 지우기_전_확인은_다시_정한_D와_넘어간_후보를_본다() {
        // given — D 는 kim 하나고 장부에 있다. 다시 정한 D' 는 비었다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        TupleReconciler.DeleteCheck 빈_조직도면_멈춘다 = (desired, stale, scanned) ->
                desired.isEmpty() && !stale.isEmpty() ? Optional.of("빈 조직도") : Optional.empty();

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김),
                (desired, stale) -> Mono.just(Set.of()), 빈_조직도면_멈춘다).block();

        // then
        assertThat(reconciliation.held()).isTrue();
        assertThat(writer.deleted).isEmpty();
        assertThat(writer.stored).containsExactly(김);
    }

    @Test
    @DisplayName("지우기 묶음이 끝날 때마다 지운 줄을 알린다 — 차단기로 멈춰도 끝난 것은 알린 뒤다")
    void 지운_줄을_묶음마다_알린다() {
        // given — 찌꺼기를 지우는 쓰기가 차단기로 멈춘다(그 전에 반영한 것은 반영됐다)
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.add(찌꺼기);
        writer.abortWhen(delta -> !delta.toDelete().isEmpty());
        List<RelationTuple> 알린_줄 = new CopyOnWriteArrayList<>();

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(RelationTuple.directMember("kim", "DEV002")),
                TupleReconciler.DesiredRevision.NONE, TupleReconciler.DeleteCheck.NONE, 알린_줄::addAll).block();

        // then
        assertThat(reconciliation.stopReason()).isNotNull();
        assertThat(알린_줄).containsExactly(찌꺼기);
    }

    // ---------- 결과를 모르는 지우기 (설계 2026-10-20 §3.4) ----------

    @Test
    @DisplayName("훑은 뒤 지우기가 불확실하게 실패하면 불확실 줄이고, 확정 실패는 장부(기준선)에 남는다")
    void 훑은_뒤_지우기가_불확실하게_실패하면_불확실_줄이고_확정_실패는_기준선에_남는다() {
        // given — 장부에 kim(D), 찌꺼기 둘. 한쪽은 지웠는데 응답을 잃고, 다른 쪽은 거절된다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 응답유실 = RelationTuple.directMember("ghost1", "DEV002");
        RelationTuple 거절 = RelationTuple.directMember("ghost2", "DEV002");
        writer.stored.addAll(Set.of(김, 응답유실, 거절));
        writer.lostResponseFor(tuple -> tuple.equals(응답유실));
        writer.failFor(tuple -> tuple.equals(거절));

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김)).block();

        // then — 결과를 모르는 줄은 불확실 줄로 가고 기준선에서 빠진다. 거절된 줄은 분명히 장부에 있으니 기준선에 남는다
        assertThat(reconciliation.uncertain()).containsExactly(응답유실);
        assertThat(reconciliation.ledger()).containsExactlyInAnyOrder(김, 거절);
        assertThat(reconciliation.ledger()).doesNotContainAnyElementsOf(reconciliation.uncertain());
        assertThat(reconciliation.result().failures()).extracting(TupleFailure::tuple)
                .containsExactlyInAnyOrder(응답유실, 거절);
        assertThat(writer.stored).as("응답을 잃은 지우기는 실제로 반영됐다").containsExactlyInAnyOrder(김, 거절);
    }

    @Test
    @DisplayName("지우기가 연속 실패 차단기로 멈춰도 결과를 모르는 줄은 불확실 줄이다")
    void 차단기로_멈춘_지우기도_불확실_줄을_남긴다() {
        // given — 찌꺼기를 지우는 쓰기가 응답을 잃은 채 차단기로 멈춘다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.add(찌꺼기);
        writer.lostResponseFor(tuple -> tuple.equals(찌꺼기));
        writer.abortWhen(delta -> !delta.toDelete().isEmpty());

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김)).block();

        // then
        assertThat(reconciliation.stopReason()).isNotNull();
        assertThat(reconciliation.uncertain()).containsExactly(찌꺼기);
        assertThat(reconciliation.ledger()).containsExactly(김);
    }

    @Test
    @DisplayName("다시 정해 더한 줄의 쓰기가 불확실하게 실패하면 불확실 줄이다 — 훑은 뒤에 쓴 줄이라 훑기가 알려 주지 못한다")
    void 더한_줄의_쓰기가_불확실하게_실패하면_불확실_줄이다() {
        // given — D 는 kim 하나. 다시 정하기가 park 을 더하는데 park 의 쓰기가 응답을 잃는다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        writer.lostResponseFor(tuple -> tuple.equals(박));

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김),
                (desired, stale) -> Mono.just(Set.of(김, 박)), TupleReconciler.DeleteCheck.NONE).block();

        // then
        assertThat(reconciliation.uncertain()).containsExactly(박);
        assertThat(reconciliation.ledger()).containsExactly(김);
    }
}

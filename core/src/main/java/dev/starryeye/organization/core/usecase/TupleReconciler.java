package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 장부(OpenFGA store)를 있어야 할 줄 D 에 맞춘다 — 재적재의 2~3단계 (설계 2026-09-29 §3.1).
 *
 * <p><b>장부를 비우는 순간이 없다.</b> D 를 먼저 전부 쓰고(이미 있는 줄은 OpenFGA 가 무시한다), 그다음 장부를 훑어 D 에 없는 줄만
 * 지운다. 쓰기와 지우기 사이에 새로 생길 권한이 잠깐 없거나 지워질 권한이 잠깐 남을 뿐, 권한 질의는 내내 정상으로 답한다. 전에는
 * 장부를 지우고 다시 만들어 그동안 모든 질의가 false 였고, 장부 번호가 바뀌었다(점검 C2).
 *
 * <p><b>훑기는 흘려 보내며 비교한다.</b> 장부 전체를 메모리에 모으지 않는다 — 드는 것은 D 와 지울 후보뿐이다.
 *
 * <p>호출자는 D 를 <b>다 읽은 뒤에만</b> 부른다. 읽기가 실패했는데 부르면 빈 D 로 장부를 비운다(점검 M13).
 *
 * <p><b>연속 실패 차단기로 멈추면(설계 §5)</b> 어느 단계에서 멈췄느냐로 갈린다.
 * <ul>
 *   <li><b>쓰기 단계</b> — {@link TupleWriteAbortedException} 을 그대로 흘린다. 장부를 훑기 전이라 무엇을 지울지 모르니 새 스냅샷을
 *       만들 근거가 없다. 작업은 차단기 사유로 FAILED 가 되고 재적재를 다시 걸어 맞춘다.</li>
 *   <li><b>지우기 단계</b> — 멈추기 전까지 나간 것으로 결과를 만든다. 보내지 않은 지우기는 실패로 들어 있어 {@code ledger} 에 남는다
 *       (설계 §3.1 4단계). 멈춘 사유는 {@link Reconciliation#stopReason()} 에 담겨 호출자가 FAILED 로 기록한다.</li>
 * </ul>
 */
@Slf4j
final class TupleReconciler {

    private TupleReconciler() {
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired) {
        return Mono.defer(() -> 쓴다(writer, desired)
                .flatMap(written -> 지울_줄을_찾는다(scanner, desired)
                        .flatMap(stale -> 지운다(writer, stale)
                                .map(deleted -> Reconciliation.of(written, deleted))
                                .onErrorResume(TupleWriteAbortedException.class,
                                        stopped -> Mono.just(Reconciliation.stopped(written, stopped))))));
    }

    private static Mono<TupleWriteResult> 쓴다(RelationTupleWriter writer, Set<RelationTuple> desired) {
        return desired.isEmpty()
                ? Mono.just(TupleWriteResult.empty())
                : writer.apply(TupleDelta.writeOnly(desired));
    }

    private static Mono<TupleWriteResult> 지운다(RelationTupleWriter writer, Set<RelationTuple> stale) {
        return stale.isEmpty()
                ? Mono.just(TupleWriteResult.empty())
                : writer.apply(TupleDelta.deleteOnly(stale));
    }

    private static Mono<Set<RelationTuple>> 지울_줄을_찾는다(RelationTupleScanner scanner, Set<RelationTuple> desired) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            AtomicLong 읽은_줄 = new AtomicLong();
            return scanner.scanAll()
                    .doOnNext(tuple -> 읽은_줄.incrementAndGet())
                    .filter(tuple -> !desired.contains(tuple))
                    .collect(Collectors.toSet())
                    .doOnNext(stale -> log.info("장부 훑기: {}줄을 읽어 있어야 할 줄에 없는 {}줄을 찾았다 ({}ms)",
                            읽은_줄.get(), stale.size(), Duration.ofNanos(System.nanoTime() - 시작).toMillis()));
        });
    }

    /**
     * 장부 청소의 결과.
     *
     * @param result     쓰기·지우기를 합친 결과 — 실행 기록의 건수가 된다
     * @param ledger     장부에 실제로 있다고 볼 줄 — 새 스냅샷이 된다 (설계 §3.1 4단계)
     * @param stopReason 지우기가 연속 실패 차단기로 멈춘 사유. 멈추지 않았으면 {@code null}
     */
    record Reconciliation(TupleWriteResult result, Set<RelationTuple> ledger, String stopReason) {

        /**
         * 스냅샷에 담을 줄 = 쓰기에 성공한 줄 ∪ 지우기에 실패한 줄. 지우지 못한 찌꺼기를 넣어야 다음 LDAP 동기화가 그 줄을
         * "있는데 없어야 할 줄"로 보고 다시 지운다. 쓰기에 실패한 줄은 뺀다 — 다음 동기화가 다시 쓴다.
         */
        static Reconciliation of(TupleWriteResult written, TupleWriteResult deleted) {
            return 합친다(written, deleted, null);
        }

        /** 지우기가 차단기로 멈췄다. 보내지 않은 지우기는 {@code partial} 에 실패로 들어 있어 {@code ledger} 에 남는다. */
        static Reconciliation stopped(TupleWriteResult written, TupleWriteAbortedException stopped) {
            return 합친다(written, stopped.partial(), stopped.getMessage());
        }

        /** 실행 기록에 남길 결론. 멈췄으면 FAILED(스냅샷은 남긴다), 아니면 실패 유무로 SUCCEEDED·PARTIAL 이다. */
        SyncOutcome outcome(String snapshotId) {
            if (stopReason != null) {
                return SyncOutcome.stopped(result, snapshotId, stopReason);
            }
            return result.hasFailure()
                    ? SyncOutcome.partial(result, snapshotId)
                    : SyncOutcome.succeeded(result, snapshotId);
        }

        private static Reconciliation 합친다(TupleWriteResult written, TupleWriteResult deleted, String stopReason) {
            Set<RelationTuple> ledger = new HashSet<>(written.written());
            deleted.failures().forEach(failure -> ledger.add(failure.tuple()));

            List<TupleFailure> failures = new ArrayList<>(written.failures());
            failures.addAll(deleted.failures());
            return new Reconciliation(
                    new TupleWriteResult(written.written(), deleted.deleted(), failures), ledger, stopReason);
        }
    }
}

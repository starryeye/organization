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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 장부(OpenFGA store)를 있어야 할 줄 D 에 맞춘다 — 재적재의 2~3단계 (설계 2026-09-29 §3.1).
 *
 * <p><b>장부를 비우는 순간이 없다.</b> D 를 먼저 전부 쓰고(이미 있는 줄은 OpenFGA 가 무시한다), 그다음 장부를 훑어 D 에 없는 줄만
 * 지운다. 쓰기와 지우기 사이에 새로 생길 권한이 잠깐 없거나 지워질 권한이 잠깐 남을 뿐, 권한 질의는 내내 정상으로 답한다.
 *
 * <p><b>지우기 전에 확인한다(설계 2026-09-30 §4.3).</b> 훑은 뒤 {@link DeleteCheck} 가 멈출 이유를 주면 지우지 않는다 — 삭제 가드(LDAP)나
 * 빈 조직도 규칙(SCIM)이 여기서 걸린다. 쓴 줄은 그대로 둔다(권한을 더하는 쪽이라 해가 없다).
 *
 * <p><b>훑기는 흘려 보내며 비교한다.</b> 장부 전체를 메모리에 모으지 않는다 — 드는 것은 D 와 지울 후보뿐이다.
 *
 * <p>호출자는 D 를 <b>다 읽은 뒤에만</b> 부른다. 읽기가 실패했는데 부르면 빈 D 로 장부를 비운다(점검 M13).
 */
@Slf4j
final class TupleReconciler {

    private TupleReconciler() {
    }

    /** 훑은 뒤·지우기 전에 멈출지 정한다. 멈출 이유를 주면 지우지 않고 {@link Reconciliation#held()} 로 끝난다. */
    @FunctionalInterface
    interface DeleteCheck {

        Optional<String> holdReason(Set<RelationTuple> desired, Set<RelationTuple> stale, long scanned);

        DeleteCheck NONE = (desired, stale, scanned) -> Optional.empty();
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired) {
        return reconcile(writer, scanner, desired, DeleteCheck.NONE);
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DeleteCheck check) {
        return Mono.defer(() -> 쓴다(writer, desired)
                .flatMap(written -> 훑는다(scanner, desired)
                        .flatMap(scan -> {
                            Optional<String> hold = check.holdReason(desired, scan.stale(), scan.scanned());
                            if (hold.isPresent()) {
                                log.warn("장부를 훑었지만 지우지 않는다: {}", hold.get());
                                return Mono.just(Reconciliation.held(written, hold.get()));
                            }
                            return 지운다(writer, scan.stale())
                                    .map(deleted -> Reconciliation.of(written, deleted))
                                    .onErrorResume(TupleWriteAbortedException.class,
                                            stopped -> Mono.just(Reconciliation.stopped(written, stopped)));
                        })));
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

    private static Mono<Scan> 훑는다(RelationTupleScanner scanner, Set<RelationTuple> desired) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            AtomicLong 읽은_줄 = new AtomicLong();
            return scanner.scanAll()
                    .doOnNext(tuple -> 읽은_줄.incrementAndGet())
                    .filter(tuple -> !desired.contains(tuple))
                    .collect(Collectors.toSet())
                    .map(stale -> new Scan(stale, 읽은_줄.get()))
                    .doOnNext(scan -> log.info("장부 훑기: {}줄을 읽어 있어야 할 줄에 없는 {}줄을 찾았다 ({}ms)",
                            scan.scanned(), scan.stale().size(), Duration.ofNanos(System.nanoTime() - 시작).toMillis()));
        });
    }

    /** 훑은 결과 — 지울 후보와 읽은 줄 수(삭제 가드의 기준). */
    private record Scan(Set<RelationTuple> stale, long scanned) {
    }

    /**
     * 장부 청소의 결과.
     *
     * @param result     쓰기·지우기를 합친 결과 — 실행 기록의 건수가 된다
     * @param ledger     장부에 실제로 있다고 볼 줄 — 새 스냅샷이 된다 (설계 §3.1 4단계)
     * @param stopReason 지우기가 연속 실패 차단기로 멈춘 사유. 멈추지 않았으면 {@code null}
     * @param heldReason 지우기 전 확인이 멈춘 사유. 멈추지 않았으면 {@code null} — 이때는 스냅샷을 남기지 않는다
     */
    record Reconciliation(TupleWriteResult result, Set<RelationTuple> ledger, String stopReason, String heldReason) {

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

        /** 지우기 전에 멈췄다 — 지운 것이 없다. 호출자는 스냅샷을 남기지 않고 사유로 끝낸다. */
        static Reconciliation held(TupleWriteResult written, String reason) {
            return new Reconciliation(written, Set.copyOf(written.written()), null, reason);
        }

        boolean held() {
            return heldReason != null;
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
                    new TupleWriteResult(written.written(), deleted.deleted(), failures), ledger, stopReason, null);
        }
    }
}

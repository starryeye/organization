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

/**
 * 장부(OpenFGA store)를 있어야 할 줄 D 에 맞춘다 — 재적재의 2~3단계 (설계 2026-09-29 §3.1).
 *
 * <p><b>장부를 비우는 순간이 없다.</b> D 를 먼저 전부 쓰고(이미 있는 줄은 OpenFGA 가 무시한다), 그다음 장부를 훑어 D 에 없는 줄만
 * 지운다. 쓰기와 지우기 사이에 새로 생길 권한이 잠깐 없거나 지워질 권한이 잠깐 남을 뿐, 권한 질의는 내내 정상으로 답한다.
 *
 * <p><b>지우기 전에 확인한다(설계 2026-09-30 §4.3).</b> 훑은 뒤 {@link DeleteCheck} 가 멈출 이유를 주면 지우지 않는다 — 삭제 가드(LDAP)나
 * 빈 조직도 규칙(SCIM)이 여기서 걸린다. 쓴 줄은 그대로 둔다(권한을 더하는 쪽이라 해가 없다).
 *
 * <p><b>지우기 전에 다시 정할 수 있다(설계 2026-10-13 §4.3).</b> {@link DesiredRevision} 이 D 를 다시 정한 D' 로 바꾼다 — 지우기 전에 D' 가 D 를
 * 대신한다. D' 에 새로 든 줄은 지우지 않고(장부에 없으면 쓴다), D 에 있었지만 D' 에 없는 줄은 장부에 있다면 지울 후보가 된다. SCIM 재적재가 GSI 열거에서
 * 빠진 대상을 본 테이블로 다시 읽어 D' 를 만든다. LDAP 장부 맞추기는 쓰지 않는다.
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

    /**
     * 훑은 뒤·지우기 전에 있어야 할 줄을 다시 정한다(설계 2026-10-13 §4.3). 처음 D 와 지울 후보를 보고 <b>다시 정한 D' 전체</b>를 준다 — 더할 것만이
     * 아니다. 바꿀 것이 없으면 받은 D 를 그대로 준다. 지우기 전에 D' 가 D 를 대신한다.
     *
     * <ul>
     *   <li>D' 에 새로 든 줄 중 장부에 있는 것(지울 후보에 든 것)은 지우지 않고, 장부에 없는 것은 쓴다.</li>
     *   <li>D 에 있었지만 D' 에 없는 줄은 장부에 있다면 지울 후보로 넘긴다. 장부에 없었다면(처음 쓰기가 실패했다) 어느 쪽에도 넣지 않는다.</li>
     * </ul>
     *
     * SCIM 재적재가 GSI 열거에서 빠진 대상을 다시 읽어 D' 를 만든다. 다시 계산한 조직도가 순환이라 D 의 연결을 버릴 수 있어, 장부와 보류 목록이 함께 D' 를 따르게 한다.
     */
    @FunctionalInterface
    interface DesiredRevision {

        Mono<Set<RelationTuple>> revise(Set<RelationTuple> desired, Set<RelationTuple> stale);

        DesiredRevision NONE = (desired, stale) -> Mono.just(desired);
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired) {
        return reconcile(writer, scanner, desired, DesiredRevision.NONE, DeleteCheck.NONE);
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DeleteCheck check) {
        return reconcile(writer, scanner, desired, DesiredRevision.NONE, check);
    }

    /**
     * D 를 쓰고, 장부를 훑어 지울 후보를 가르고, 지우기 전에 {@code revision} 으로 D 를 다시 정한 D' 로 바꾼 뒤, {@code check} 를 거쳐 지운다.
     * {@code check} 는 D' 와 남은 후보를 본다.
     */
    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DesiredRevision revision, DeleteCheck check) {
        return Mono.defer(() -> 쓴다(writer, desired)
                .flatMap(written -> 훑는다(scanner, desired)
                        .flatMap(scan -> revision.revise(desired, scan.stale())
                                .flatMap(다시_정한_D -> 다시_정한다(writer, desired, written, scan, 다시_정한_D))))
                .flatMap(revised -> {
                    Scan scan = revised.scan();
                    Optional<String> hold = check.holdReason(revised.desired(), scan.stale(), scan.scanned());
                    if (hold.isPresent()) {
                        log.warn("장부를 훑었지만 지우지 않는다: {}", hold.get());
                        return Mono.just(Reconciliation.held(revised.written(), hold.get()));
                    }
                    return 지운다(writer, scan.stale())
                            .map(deleted -> Reconciliation.of(revised.written(), deleted, scan.present()))
                            .onErrorResume(TupleWriteAbortedException.class,
                                    stopped -> Mono.just(Reconciliation.stopped(revised.written(), stopped, scan.present())));
                }));
    }

    /** 다시 정한 결과 — D' (다시 정한 D), 쓰기 결과(더 쓴 줄 포함), 지울 후보와 장부에 있다고 볼 줄. */
    private record 다시_정함(Set<RelationTuple> desired, TupleWriteResult written, Scan scan) {
    }

    /**
     * D 를 다시 정한 D' 로 바꾼다.
     * <ul>
     *   <li>더한 줄(D'∖D) 중 장부에 있는 것(지울 후보에 든 것)은 후보에서 빼 장부에 있는 줄로 옮기고, 나머지는 쓴다. 실제로 쓴 줄만 장부에 있다고 본다.</li>
     *   <li>뺀 줄(D∖D') 중 장부에 있던 것은 장부에 있는 줄에서 빼 지울 후보에 넣는다. 장부에 없던 것은 어느 쪽에도 넣지 않는다.</li>
     * </ul>
     * 바뀐 것이 없으면 받은 그대로다 — 쓰기가 없다.
     */
    private static Mono<다시_정함> 다시_정한다(RelationTupleWriter writer, Set<RelationTuple> desired, TupleWriteResult written,
                                        Scan scan, Set<RelationTuple> revised) {
        // 같은 객체면 바뀐 것이 없다 — 평소(LDAP 장부 맞춤·초기화·바뀐 것 없는 SCIM 재적재)에는 |D| 크기의 집합 둘을 만들지 않는다
        if (revised == desired) {
            return Mono.just(new 다시_정함(desired, written, scan));
        }
        Set<RelationTuple> 더한 = new HashSet<>(revised);
        더한.removeAll(desired);
        Set<RelationTuple> 뺀 = new HashSet<>(desired);
        뺀.removeAll(revised);
        if (더한.isEmpty() && 뺀.isEmpty()) {
            return Mono.just(new 다시_정함(desired, written, scan));
        }
        Set<RelationTuple> 장부에_있던_더한 = new HashSet<>(더한);
        장부에_있던_더한.retainAll(scan.stale());
        Set<RelationTuple> 쓸것 = new HashSet<>(더한);
        쓸것.removeAll(장부에_있던_더한);
        Set<RelationTuple> 장부에_있던_뺀 = new HashSet<>(뺀);
        장부에_있던_뺀.retainAll(scan.present());
        Set<RelationTuple> 남은_후보 = new HashSet<>(scan.stale());
        남은_후보.removeAll(revised);
        남은_후보.addAll(장부에_있던_뺀);
        log.warn("지우기 전에 있어야 할 줄을 다시 정했다 — 더한 {}줄(장부에 있던 {}줄은 지우지 않고 {}줄은 새로 쓴다), 뺀 {}줄(장부에 있던 {}줄은 지울 후보로 넘긴다)",
                더한.size(), 장부에_있던_더한.size(), 쓸것.size(), 뺀.size(), 장부에_있던_뺀.size());
        return 쓴다(writer, 쓸것).map(더_쓴 -> {
            Set<RelationTuple> present = new HashSet<>(scan.present());
            present.removeAll(뺀);
            present.addAll(장부에_있던_더한);
            present.addAll(더_쓴.written());
            return new 다시_정함(revised, 쓴_결과를_합친다(written, 더_쓴), new Scan(남은_후보, present, scan.scanned()));
        });
    }

    /** 처음 쓰기와 더 쓴 결과를 합친다 — 지운 것은 아직 없다. */
    private static TupleWriteResult 쓴_결과를_합친다(TupleWriteResult first, TupleWriteResult more) {
        Set<RelationTuple> written = new HashSet<>(first.written());
        written.addAll(more.written());
        List<TupleFailure> failures = new ArrayList<>(first.failures());
        failures.addAll(more.failures());
        return new TupleWriteResult(written, first.deleted(), failures);
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

    /**
     * 장부를 흘려 보내며 한 번에 {@code stale}(D 에 없는 줄)과 {@code present}(D 에 있는, 장부가 실제로 담고 있는 줄)로 가른다.
     * 장부 전체를 모으지 않는다 — 드는 것은 {@code present}(≤ |D|)와 {@code stale}뿐이다.
     */
    private static Mono<Scan> 훑는다(RelationTupleScanner scanner, Set<RelationTuple> desired) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            AtomicLong 읽은_줄 = new AtomicLong();
            Set<RelationTuple> stale = new HashSet<>();
            Set<RelationTuple> present = new HashSet<>();
            return scanner.scanAll()
                    .doOnNext(tuple -> {
                        읽은_줄.incrementAndGet();
                        if (desired.contains(tuple)) {
                            present.add(tuple);
                        } else {
                            stale.add(tuple);
                        }
                    })
                    .then(Mono.fromSupplier(() -> new Scan(stale, present, 읽은_줄.get())))
                    .doOnNext(scan -> log.info("장부 훑기: {}줄을 읽어 있어야 할 줄에 없는 {}줄을 찾았다 ({}ms)",
                            scan.scanned(), scan.stale().size(), Duration.ofNanos(System.nanoTime() - 시작).toMillis()));
        });
    }

    /** 훑은 결과 — 지울 후보(stale), 장부에 있다고 확인된 D 의 줄(present, 새 스냅샷의 재료), 읽은 줄 수(삭제 가드의 기준). */
    private record Scan(Set<RelationTuple> stale, Set<RelationTuple> present, long scanned) {
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
         * 스냅샷에 담을 줄 = 훑을 때 장부에 있던 있어야 할 줄(present) ∪ 지우기에 실패한 줄. 스캔은 쓰기 단계 뒤에 돌므로, 다시 쓰기가
         * 실패한 줄이라도 전부터 장부에 있었다면(present 에 있다면) 여전히 담는다 — 빼면 그 사람이 나중에 조직도에서 빠져도 지울 기준이
         * 없어 권한이 영원히 남는다(점검 M2). 애초에 장부에 없었는데 쓰기까지 실패한 줄은 present 에 없으니 자연히 빠진다 — 다음
         * 동기화가 다시 쓴다.
         */
        static Reconciliation of(TupleWriteResult written, TupleWriteResult deleted, Set<RelationTuple> present) {
            return 합친다(written, deleted, present, null);
        }

        /** 지우기가 차단기로 멈췄다. 보내지 않은 지우기는 {@code partial} 에 실패로 들어 있어 {@code ledger} 에 남는다. */
        static Reconciliation stopped(TupleWriteResult written, TupleWriteAbortedException stopped,
                                      Set<RelationTuple> present) {
            return 합친다(written, stopped.partial(), present, stopped.getMessage());
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

        private static Reconciliation 합친다(TupleWriteResult written, TupleWriteResult deleted,
                                          Set<RelationTuple> present, String stopReason) {
            Set<RelationTuple> ledger = new HashSet<>(present);
            deleted.failures().forEach(failure -> ledger.add(failure.tuple()));

            List<TupleFailure> failures = new ArrayList<>(written.failures());
            failures.addAll(deleted.failures());
            return new Reconciliation(
                    new TupleWriteResult(written.written(), deleted.deleted(), failures), ledger, stopReason, null);
        }
    }
}

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
import java.util.function.Consumer;

/**
 * 장부(OpenFGA store)를 있어야 할 줄 D 에 맞춘다 — 재적재의 2~3단계 (설계 2026-09-29 §3.1).
 *
 * <p><b>장부를 비우는 순간이 없다.</b> D 를 먼저 전부 쓰고(이미 있는 줄은 OpenFGA 가 무시한다), 그다음 장부를 훑어 D 에 없는 줄만
 * 지운다. 쓰기와 지우기 사이에 새로 생길 권한이 잠깐 없거나 지워질 권한이 잠깐 남을 뿐, 권한 질의는 내내 정상으로 답한다.
 *
 * <p><b>지우기 전에 확인한다(설계 2026-09-30 §4.3, 2026-10-20 §5.4).</b> 훑은 뒤 {@link DeleteCheck} 가 지울 줄과 멈출 이유를 정한다 — 삭제
 * 가드(LDAP)나 빈 조직도 규칙(SCIM)이 여기서 걸린다. 전부 보류면 지우지 않고, 일부 반영(가드가 걸렸지만 디렉터리가 막았다고 명시한 회수는 통과)이면 그것만
 * 지운다. 보류한 후보는 장부에 그대로 있으니 새 스냅샷의 기준선(B)에 든다. 쓴 줄은 그대로 둔다(권한을 더하는 쪽이라 해가 없다).
 *
 * <p><b>지우기 전에 다시 정할 수 있다(설계 2026-10-13 §4.3).</b> {@link DesiredRevision} 이 D 를 다시 정한 D' 로 바꾼다 — 지우기 전에 D' 가 D 를
 * 대신한다. D' 에 새로 든 줄은 지우지 않고(장부에 없으면 쓴다), D 에 있었지만 D' 에 없는 줄은 장부에 있다면 지울 후보가 된다. SCIM 재적재가 GSI 열거에서
 * 빠진 대상을 본 테이블로 다시 읽어 D' 를 만든다. LDAP 장부 맞추기는 쓰지 않는다.
 *
 * <p><b>결과를 모르는 줄은 불확실 줄로 남는다(설계 2026-10-20 §3.4).</b> 일시 오류로 결과를 모르는 줄은 새 스냅샷의 기준선(B)이 아니라 불확실 줄(U)에
 * 담는다 — 다음 회차가 목표에 따라 다시 쓰거나 지운다. U 에 드는 것은 셋이다.
 * <ul>
 *   <li>훑기 전 D 쓰기가 결과를 모른 채 실패했고 훑을 때 장부에 없던 줄 — 클라이언트가 포기한 뒤, 훑은 뒤에 서버가 반영할 수 있다. 훑을 때 있던 줄은
 *       훑기가 실제를 알려 주었으니 B 다.</li>
 *   <li>{@link DesiredRevision} 으로 다시 정하며 훑은 뒤에 더 쓰다가 결과를 모르게 된 줄 — 훑기가 알려 주지 못한다.</li>
 *   <li>훑은 뒤의 지우기가 결과를 모른 채 실패한 줄.</li>
 * </ul>
 * 거절된 줄과 보내지 않은 줄(차단기, 가드 보류)은 장부에 그대로 있다는 것을 아니까 B 에 남긴다. B 와 U 는 겹치지 않는다.
 *
 * <p><b>훑기는 흘려 보내며 비교한다.</b> 장부 전체를 메모리에 모으지 않는다 — 드는 것은 D 와 지울 후보뿐이다.
 *
 * <p>호출자는 D 를 <b>다 읽은 뒤에만</b> 부른다. 읽기가 실패했는데 부르면 빈 D 로 장부를 비운다(점검 M13).
 */
@Slf4j
final class TupleReconciler {

    private TupleReconciler() {
    }

    /**
     * 훑은 뒤·지우기 전에 무엇을 지울지 정한다. D'({@code desired}), 지울 후보({@code stale}), 읽은 줄 수({@code scanned})를 본다. 전부 보류면 지우지 않고
     * {@link Reconciliation#heldEntirely()} 로, 일부 반영이면 내준 것만 지우고 {@link Reconciliation#revokedOnly()} 로 끝난다.
     */
    @FunctionalInterface
    interface DeleteCheck {

        DeleteVerdict verdict(Set<RelationTuple> desired, Set<RelationTuple> stale, long scanned);

        DeleteCheck NONE = (desired, stale, scanned) -> DeleteVerdict.proceed(stale);
    }

    /**
     * 지우기 전 확인의 판정 — 지울 줄과 멈춘 이유.
     * <ul>
     *   <li>진행: ({@code stale}, {@code null})</li>
     *   <li>전부 보류: (빈 집합, 사유)</li>
     *   <li>일부 반영: ({@code stale} 의 일부, 사유) — 가드가 걸렸지만 디렉터리가 막았다고 명시한 회수는 통과했다(설계 2026-10-20 §5.4)</li>
     * </ul>
     *
     * @param toDelete   지울 줄. 지울 후보({@code stale})의 부분집합이다
     * @param holdReason 지우지 않고 남기는 후보가 있으면 그 사유 — 실행 기록의 결론이 된다. 진행이면 {@code null}
     */
    record DeleteVerdict(Set<RelationTuple> toDelete, String holdReason) {

        /** 하나도 지우지 않는다 — 스냅샷을 남기지 않는다. */
        boolean heldEntirely() {
            return holdReason != null && toDelete.isEmpty();
        }

        static DeleteVerdict proceed(Set<RelationTuple> stale) {
            return new DeleteVerdict(stale, null);
        }

        static DeleteVerdict holdAll(String reason) {
            return new DeleteVerdict(Set.of(), reason);
        }

        static DeleteVerdict partial(Set<RelationTuple> toDelete, String reason) {
            return new DeleteVerdict(toDelete, reason);
        }
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

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DesiredRevision revision, DeleteCheck check) {
        return reconcile(writer, scanner, desired, revision, check, 지운_줄 -> {
        });
    }

    /** 쓴 줄은 알리지 않는다 — 재적재·"기록 중" 장부 맞추기의 이력은 쓴 줄을 남기지 않는다(설계 2026-10-10 §3.5). */
    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DesiredRevision revision, DeleteCheck check,
                                          Consumer<Set<RelationTuple>> 지운_줄마다) {
        return reconcile(writer, scanner, desired, revision, check, 지운_줄마다, 쓴_묶음 -> {
        });
    }

    /**
     * D 를 쓰고, 장부를 훑어 지울 후보를 가르고, 지우기 전에 {@code revision} 으로 D 를 다시 정한 D' 로 바꾼 뒤, {@code check} 를 거쳐 지운다.
     * {@code check} 는 D' 와 남은 후보를 본다. {@code 지운_줄마다} 는 지우기 묶음이 끝날 때마다 실제로 지운 줄로 불린다(설계 2026-10-15 §5) — 차단기로
     * 멈추거나 취소돼도 끝난 묶음은 이미 알린 뒤다. 여러 스레드에서 불릴 수 있다.
     *
     * <p>{@code 쓴_묶음마다} 는 쓰기 묶음이 끝날 때마다 그 묶음의 결과(쓴 줄·실패)로 불린다 — 첫 쓰기와 훑은 뒤 더 쓰는 쓰기 모두다. 기준선이 없는 첫 회차의
     * 부여 이력이 이것으로 남는다(설계 2026-10-20 §4). 쓴 줄은 중복 무시로 써 이미 있던 줄과 가를 수 없다는 한계는 그대로다.
     */
    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DesiredRevision revision, DeleteCheck check,
                                          Consumer<Set<RelationTuple>> 지운_줄마다,
                                          Consumer<TupleWriteResult> 쓴_묶음마다) {
        return Mono.defer(() -> 쓴다(writer, desired, 쓴_묶음마다)
                .flatMap(written -> 훑는다(scanner, desired)
                        .map(scan -> scan.훑을_때_없던_불확실_쓰기를_더한다(written))
                        .flatMap(scan -> revision.revise(desired, scan.stale())
                                .flatMap(다시_정한_D -> 다시_정한다(writer, desired, written, scan, 다시_정한_D, 쓴_묶음마다))))
                .flatMap(revised -> {
                    Scan scan = revised.scan();
                    DeleteVerdict verdict = check.verdict(revised.desired(), scan.stale(), scan.scanned());
                    if (verdict.heldEntirely()) {
                        log.warn("장부를 훑었지만 지우지 않는다: {}", verdict.holdReason());
                        return Mono.just(Reconciliation.held(revised.written(), verdict.holdReason()));
                    }
                    if (verdict.holdReason() != null) {
                        log.warn("장부를 훑었지만 지울 후보 {}줄 가운데 {}줄만 지운다: {}",
                                scan.stale().size(), verdict.toDelete().size(), verdict.holdReason());
                    }
                    return 지운다(writer, verdict.toDelete(), 지운_줄마다)
                            .map(deleted -> Reconciliation.of(revised.written(), deleted, scan, verdict.holdReason()))
                            .onErrorResume(TupleWriteAbortedException.class, stopped -> Mono.just(
                                    Reconciliation.stopped(revised.written(), stopped, scan, verdict.holdReason())));
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
     *
     * <p>더 쓴 줄은 훑은 <b>뒤</b>에 보내므로 훑기가 결과를 알려 주지 못한다. 결과를 모르는 채 실패한 줄은 {@link Scan#uncertain()} 에 더한다 — 이미 든
     * 줄(훑기 전 쓰기가 결과를 모르고 훑을 때 없던 줄)은 그대로 둔다. 더한 줄은 D 밖이라 그 줄과 겹치지 않는다.
     */
    private static Mono<다시_정함> 다시_정한다(RelationTupleWriter writer, Set<RelationTuple> desired, TupleWriteResult written,
                                        Scan scan, Set<RelationTuple> revised, Consumer<TupleWriteResult> 쓴_묶음마다) {
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
        return 쓴다(writer, 쓸것, 쓴_묶음마다).map(더_쓴 -> {
            Set<RelationTuple> present = new HashSet<>(scan.present());
            present.removeAll(뺀);
            present.addAll(장부에_있던_더한);
            present.addAll(더_쓴.written());
            Set<RelationTuple> uncertain = new HashSet<>(scan.uncertain());
            uncertain.addAll(더_쓴.uncertain());
            return new 다시_정함(revised, 쓴_결과를_합친다(written, 더_쓴),
                    new Scan(남은_후보, present, scan.scanned(), uncertain));
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

    private static Mono<TupleWriteResult> 쓴다(RelationTupleWriter writer, Set<RelationTuple> desired,
                                            Consumer<TupleWriteResult> 쓴_묶음마다) {
        return desired.isEmpty()
                ? Mono.just(TupleWriteResult.empty())
                : writer.apply(TupleDelta.writeOnly(desired), 쓴_묶음마다);
    }

    private static Mono<TupleWriteResult> 지운다(RelationTupleWriter writer, Set<RelationTuple> stale,
                                              Consumer<Set<RelationTuple>> 지운_줄마다) {
        return stale.isEmpty()
                ? Mono.just(TupleWriteResult.empty())
                : writer.apply(TupleDelta.deleteOnly(stale), 묶음 -> 지운_줄마다.accept(묶음.deleted()));
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
                    .then(Mono.fromSupplier(() -> new Scan(stale, present, 읽은_줄.get(), Set.of())))
                    .doOnNext(scan -> log.info("장부 훑기: {}줄을 읽어 있어야 할 줄에 없는 {}줄을 찾았다 ({}ms)",
                            scan.scanned(), scan.stale().size(), Duration.ofNanos(System.nanoTime() - 시작).toMillis()));
        });
    }

    /**
     * 훑은 결과 — 지울 후보(stale), 장부에 있다고 확인된 D 의 줄(present, 새 스냅샷의 재료), 읽은 줄 수(삭제 가드의 기준), 지우기 전까지 결과를 모르게 된
     * 쓰기(uncertain — 훑기 전 쓰기가 결과를 모르고 훑을 때 없던 줄, 훑은 뒤 더 쓰다가 결과를 모르게 된 줄).
     */
    private record Scan(Set<RelationTuple> stale, Set<RelationTuple> present, long scanned, Set<RelationTuple> uncertain) {

        /**
         * 훑기 전 쓰기에서 결과를 모른 채 실패했는데 훑을 때 장부에 없던 줄을 불확실 줄에 더한다. 클라이언트가 포기한 뒤 서버가 늦게 — 훑은 뒤에 — 반영할 수
         * 있어 훑기가 실제를 알려 주지 못한다. 훑을 때 있던 줄은 반영된 것이 확실하니 present 에 그대로 둔다. 그런 줄이 없으면 그대로다.
         */
        Scan 훑을_때_없던_불확실_쓰기를_더한다(TupleWriteResult 훑기_전_쓰기) {
            Set<RelationTuple> 불확실한_쓰기 = 훑기_전_쓰기.uncertain();
            if (불확실한_쓰기.isEmpty()) {
                return this;
            }
            Set<RelationTuple> 더한 = new HashSet<>(uncertain);
            for (RelationTuple tuple : 불확실한_쓰기) {
                if (!present.contains(tuple)) {
                    더한.add(tuple);
                }
            }
            return new Scan(stale, present, scanned, 더한);
        }
    }

    /**
     * 장부 청소의 결과.
     *
     * @param result       쓰기·지우기를 합친 결과 — 실행 기록의 건수가 된다
     * @param ledger       장부에 있다고 확실히 아는 줄 — 새 스냅샷의 B 가 된다 (설계 2026-10-20 §3.4)
     * @param uncertain    보냈는데 결과를 몰라 장부에 있는지 모르는 줄 — 새 스냅샷의 U 가 된다. {@code ledger} 와 겹치지 않는다
     * @param stopReason   지우기가 연속 실패 차단기로 멈춘 사유. 멈추지 않았으면 {@code null}
     * @param heldReason   지우기 전 확인이 후보를 남긴 사유(전부 보류이거나 일부 반영). 다 지우러 갔으면 {@code null}
     * @param heldEntirely 지우기 전 확인이 하나도 지우지 않게 했다 — 이때는 스냅샷을 남기지 않는다
     */
    record Reconciliation(TupleWriteResult result, Set<RelationTuple> ledger, Set<RelationTuple> uncertain,
                          String stopReason, String heldReason, boolean heldEntirely) {

        /**
         * 스냅샷에 담을 줄을 가른다(설계 2026-10-20 §3.4).
         * <ul>
         *   <li><b>ledger(B)</b> = 훑을 때 장부에 있던 있어야 할 줄(present) ∪ 지울 후보 가운데 지워졌다고도, 결과를 모른다고도 할 수 없는 줄.
         *       후보 가운데 지워지지 않은 것이 확실한 줄은 거절된 줄, 보내지 않은 줄(차단기로 멈춤), 지우기 전 확인이 보류한 줄(가드의 일부 반영,
         *       설계 §5.4)이다. 스캔은 쓰기 단계 뒤에 돌므로, 다시 쓰기가 실패한 줄이라도 전부터 장부에 있었다면(present 에 있다면) 여전히 담는다 —
         *       빼면 그 사람이 나중에 조직도에서 빠져도 지울 기준이 없어 권한이 영원히 남는다(점검 M2). 애초에 장부에 없었는데 쓰기까지 확정 실패한
         *       줄은 present 에 없으니 자연히 빠진다 — 다음 동기화가 다시 쓴다.</li>
         *   <li><b>uncertain(U)</b> = 훑기 전 쓰기가 결과를 모른 채 실패했고 훑을 때 없던 줄 ∪ 훑은 뒤 더 쓰다가 결과를 모른 채 실패한 줄 ∪ 지우기가
         *       결과를 모른 채 실패한 줄. 지운 줄도 쓴 줄도 아니고 B 에도 넣지 않는다 — 다음 회차가 목표에 따라 다시 쓰거나 지운다.</li>
         * </ul>
         * 마지막에 B 에서 U 를 뺀다 — 두 집합이 겹치지 않는 것을 여기서 닫는다.
         */
        static Reconciliation of(TupleWriteResult written, TupleWriteResult deleted, Scan scan, String heldReason) {
            return 합친다(written, deleted, scan, null, heldReason);
        }

        /** 지우기가 차단기로 멈췄다. 보내지 않은 지우기는 {@code partial} 에 확정 실패로 들어 있고, 후보 가운데 지워지지 않은 것이 확실한 줄로 {@code ledger} 에 남는다. */
        static Reconciliation stopped(TupleWriteResult written, TupleWriteAbortedException stopped, Scan scan,
                                      String heldReason) {
            return 합친다(written, stopped.partial(), scan, stopped.getMessage(), heldReason);
        }

        /** 지우기 전에 멈췄다 — 지운 것이 없다. 호출자는 스냅샷을 남기지 않고 사유로 끝낸다. */
        static Reconciliation held(TupleWriteResult written, String reason) {
            return new Reconciliation(written, Set.copyOf(written.written()), Set.of(), null, reason, true);
        }

        /** 지우기 전 확인이 일부만 지우게 했다(가드의 일부 반영, 설계 2026-10-20 §5.4) — 스냅샷은 남기고 현재상태는 바꾸지 않는다. */
        boolean revokedOnly() {
            return heldReason != null && !heldEntirely;
        }

        /**
         * 실행 기록에 남길 결론. 차단기로 멈췄으면 FAILED, 일부만 반영했으면 ABORTED(설계 2026-10-20 §5.3), 아니면 실패 유무로 SUCCEEDED·PARTIAL 이다.
         * 어느 쪽이든 스냅샷은 남긴다. 둘 다면 FAILED 이고 메시지는 보류 사유 뒤에 멈춘 사유를 잇는다.
         */
        SyncOutcome outcome(String snapshotId) {
            if (stopReason != null) {
                return SyncOutcome.stopped(result, snapshotId, heldReason == null ? stopReason : heldReason + " / " + stopReason);
            }
            if (heldReason != null) {
                return SyncOutcome.revokedOnly(result, snapshotId, heldReason);
            }
            return result.hasFailure()
                    ? SyncOutcome.partial(result, snapshotId)
                    : SyncOutcome.succeeded(result, snapshotId);
        }

        private static Reconciliation 합친다(TupleWriteResult written, TupleWriteResult deleted, Scan scan,
                                          String stopReason, String heldReason) {
            Set<RelationTuple> uncertain = new HashSet<>(scan.uncertain());
            uncertain.addAll(deleted.uncertain());

            Set<RelationTuple> ledger = new HashSet<>(scan.present());
            for (RelationTuple candidate : scan.stale()) {
                if (!deleted.deleted().contains(candidate)) {
                    ledger.add(candidate);
                }
            }
            ledger.removeAll(uncertain);

            List<TupleFailure> failures = new ArrayList<>(written.failures());
            failures.addAll(deleted.failures());
            return new Reconciliation(new TupleWriteResult(written.written(), deleted.deleted(), failures),
                    ledger, uncertain, stopReason, heldReason, false);
        }
    }
}

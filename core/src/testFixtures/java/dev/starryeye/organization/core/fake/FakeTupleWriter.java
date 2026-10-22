package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class FakeTupleWriter implements RelationTupleWriter {

    public final List<TupleDelta> appliedDeltas = new ArrayList<>();

    /** 지금까지 실제로 쓰인/지워진 튜플. appliedDeltas 로도 볼 수 있지만 단언이 읽기 어려워진다. */
    public final Set<RelationTuple> written = new LinkedHashSet<>();
    public final Set<RelationTuple> deleted = new LinkedHashSet<>();

    /**
     * 가짜 장부 — 쓰면 들어가고 지우면 빠진다. {@link FakeTupleScanner} 가 이것을 훑는다.
     * 테스트가 직접 넣어 "이미 있던 권한"이나 "이 서버를 거치지 않고 들어온 찌꺼기"를 흉내 낸다.
     */
    public final Set<RelationTuple> stored = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 쓰기 응답을 늦춘다. 긴 작업을 흉내내는 데 쓴다. */
    public Duration delay = Duration.ZERO;

    /** 이 조건에 걸리는 튜플은 적용에 실패한 것으로 처리한다 */
    private Predicate<RelationTuple> failWhen = tuple -> false;
    private Predicate<RelationTuple> lostResponseWhen = tuple -> false;
    private Predicate<TupleDelta> abortWhen = delta -> false;
    private Runnable applyHook;

    /** {@link #apply} 가 불릴 때 함께 실행된다. 작업 도중의 상태(게이트 등)를 들여다보는 데 쓴다. */
    public void onApply(Runnable hook) {
        this.applyHook = hook;
    }

    public void failFor(Predicate<RelationTuple> failWhen) {
        this.failWhen = failWhen;
    }

    /**
     * 이 조건에 걸리는 튜플은 "서버는 반영했는데 응답을 잃은" 것처럼 처리한다 — 가짜 장부({@link #stored}, {@link #written}·{@link #deleted} 기록)에는
     * 실제로 반영하되, 결과에는 쓴·지운 줄로 넣지 않고 불확실 실패("테스트용 응답 유실")로 넣는다. {@link #failFor} 와 겹치면 {@code failFor} 가 이긴다.
     */
    public void lostResponseFor(Predicate<RelationTuple> lostResponseWhen) {
        this.lostResponseWhen = lostResponseWhen;
    }

    /**
     * 이 조건에 걸리는 델타는 평소처럼 적용한 뒤({@link #failFor} 도 따른다) 연속 실패 차단기에 멈춘 것처럼
     * {@link TupleWriteAbortedException} 으로 끝낸다. 그 결과가 {@code partial} 이 된다.
     */
    public void abortWhen(Predicate<TupleDelta> abortWhen) {
        this.abortWhen = abortWhen;
    }

    /**
     * 델타 전체를 묶음 하나로 보고, 반영하자마자 알린 뒤 결과를 낸다(설계 2026-10-15 §4). {@link #delay} 는 알린 뒤에 늦춘다 — 묶음은 끝났는데 전체 응답이
     * 늦는 사이 취소돼도 반영한 것은 이미 알린 뒤다. {@link #abortWhen} 에 걸리면 반영한 것을 알린 뒤 멈춘다.
     */
    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta, Consumer<TupleWriteResult> 묶음마다) {
        if (applyHook != null) {
            applyHook.run();
        }
        appliedDeltas.add(delta);

        Set<RelationTuple> written = new HashSet<>();
        Set<RelationTuple> deleted = new HashSet<>();
        List<TupleFailure> failures = new ArrayList<>();

        for (RelationTuple tuple : delta.toWrite()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else if (lostResponseWhen.test(tuple)) {
                this.written.add(tuple);
                stored.add(tuple);
                failures.add(TupleFailure.uncertain(tuple, "테스트용 응답 유실"));
            } else {
                written.add(tuple);
                this.written.add(tuple);
                stored.add(tuple);
            }
        }
        for (RelationTuple tuple : delta.toDelete()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else if (lostResponseWhen.test(tuple)) {
                this.deleted.add(tuple);
                stored.remove(tuple);
                failures.add(TupleFailure.uncertain(tuple, "테스트용 응답 유실"));
            } else {
                deleted.add(tuple);
                this.deleted.add(tuple);
                stored.remove(tuple);
            }
        }
        TupleWriteResult applied = new TupleWriteResult(written, deleted, failures);
        묶음마다.accept(applied);
        Mono<TupleWriteResult> result = abortWhen.test(delta)
                ? Mono.error(new TupleWriteAbortedException("테스트용 차단 — 연속 실패로 멈췄다", applied))
                : Mono.just(applied);
        return delay.isZero() ? result : result.delayElement(delay);
    }
}

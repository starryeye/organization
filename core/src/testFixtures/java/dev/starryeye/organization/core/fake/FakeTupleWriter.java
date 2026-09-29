package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private Runnable applyHook;

    /** {@link #apply} 가 불릴 때 함께 실행된다. 작업 도중의 상태(게이트 등)를 들여다보는 데 쓴다. */
    public void onApply(Runnable hook) {
        this.applyHook = hook;
    }

    public void failFor(Predicate<RelationTuple> failWhen) {
        this.failWhen = failWhen;
    }

    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta) {
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
            } else {
                written.add(tuple);
                this.written.add(tuple);
                stored.add(tuple);
            }
        }
        for (RelationTuple tuple : delta.toDelete()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                deleted.add(tuple);
                this.deleted.add(tuple);
                stored.remove(tuple);
            }
        }
        Mono<TupleWriteResult> result = Mono.just(new TupleWriteResult(written, deleted, failures));
        return delay.isZero() ? result : result.delayElement(delay);
    }
}

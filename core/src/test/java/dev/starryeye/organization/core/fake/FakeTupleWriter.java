package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class FakeTupleWriter implements RelationTupleWriter {

    public final List<TupleDelta> appliedDeltas = new ArrayList<>();
    public final AtomicInteger resetStoreCount = new AtomicInteger();

    /** 이 조건에 걸리는 튜플은 적용에 실패한 것으로 처리한다 */
    private Predicate<RelationTuple> failWhen = tuple -> false;

    public void failFor(Predicate<RelationTuple> failWhen) {
        this.failWhen = failWhen;
    }

    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta) {
        appliedDeltas.add(delta);

        Set<RelationTuple> written = new HashSet<>();
        Set<RelationTuple> deleted = new HashSet<>();
        List<TupleFailure> failures = new ArrayList<>();

        for (RelationTuple tuple : delta.toWrite()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                written.add(tuple);
            }
        }
        for (RelationTuple tuple : delta.toDelete()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                deleted.add(tuple);
            }
        }
        return Mono.just(new TupleWriteResult(written, deleted, failures));
    }

    @Override
    public Mono<Void> resetStore() {
        resetStoreCount.incrementAndGet();
        return Mono.empty();
    }
}

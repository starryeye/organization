package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class FakeTupleWriter implements RelationTupleWriter {

    public final List<TupleDelta> appliedDeltas = new ArrayList<>();
    public final AtomicInteger resetStoreCount = new AtomicInteger();

    /** 지금까지 실제로 쓰인/지워진 튜플. appliedDeltas 로도 볼 수 있지만 단언이 읽기 어려워진다. */
    public final Set<RelationTuple> written = new LinkedHashSet<>();
    public final Set<RelationTuple> deleted = new LinkedHashSet<>();

    /** 이 조건에 걸리는 튜플은 적용에 실패한 것으로 처리한다 */
    private Predicate<RelationTuple> failWhen = tuple -> false;
    private RuntimeException resetStoreError;
    private Runnable applyHook;

    /** 설정하면 {@link #resetStore()} 가 이 예외로 실패한다. 초기화 실패 경로를 보는 데 쓴다. */
    public void failResetStore(RuntimeException error) {
        this.resetStoreError = error;
    }

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
            }
        }
        for (RelationTuple tuple : delta.toDelete()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                deleted.add(tuple);
                this.deleted.add(tuple);
            }
        }
        return Mono.just(new TupleWriteResult(written, deleted, failures));
    }

    @Override
    public Mono<Void> resetStore() {
        return Mono.defer(() -> {
            if (resetStoreError != null) {
                return Mono.error(resetStoreError);
            }
            resetStoreCount.incrementAndGet();
            return Mono.empty();
        });
    }
}

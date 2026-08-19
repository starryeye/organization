package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@link #allowed} 에 넣은 튜플만 true 로 답한다. 즉 "OpenFGA 에 실제로 있는 것" 을 흉내낸다.
 * {@link #failFor} 으로 조건을 걸면 그 튜플의 Check 가 실패한다 — 실패가 null 로 흐르는지 보는 데 쓴다.
 */
public class FakeTupleChecker implements RelationTupleChecker {

    public final Set<RelationTuple> allowed = new LinkedHashSet<>();
    public final List<RelationTuple> checked = new ArrayList<>();

    /** 이 조건에 걸리는 튜플은 Check 에 실패한 것으로 처리한다 */
    private Predicate<RelationTuple> failWhen = tuple -> false;

    public void failFor(Predicate<RelationTuple> failWhen) {
        this.failWhen = failWhen;
    }

    @Override
    public Mono<Boolean> check(RelationTuple tuple) {
        return Mono.fromCallable(() -> {
            checked.add(tuple);
            if (failWhen.test(tuple)) {
                throw new IllegalStateException("Check 실패(테스트)");
            }
            return allowed.contains(tuple);
        });
    }
}

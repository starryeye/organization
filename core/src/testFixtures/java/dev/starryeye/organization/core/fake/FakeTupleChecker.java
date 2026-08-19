package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link #allowed} 에 넣은 튜플만 true 로 답한다. 즉 "OpenFGA 에 실제로 있는 것" 을 흉내낸다.
 * {@link #failFor} 으로 조건을 걸면 그 튜플의 Check 가 실패한다 — 실패가 null 로 흐르는지 보는 데 쓴다.
 */
public class FakeTupleChecker implements RelationTupleChecker {

    public final Set<RelationTuple> allowed = new LinkedHashSet<>();
    public final List<RelationTuple> checked = new ArrayList<>();

    /** 동시에 열려 있던 Check 의 최대 개수. 직렬 호출과 병렬 호출을 구분하는 데 쓴다. */
    public final AtomicInteger maxInFlight = new AtomicInteger();

    private final AtomicInteger inFlight = new AtomicInteger();

    /** 이 조건에 걸리는 튜플은 Check 에 실패한 것으로 처리한다 */
    private Predicate<RelationTuple> failWhen = tuple -> false;

    /**
     * 튜플별 응답 지연. 기본은 즉시 응답이다.
     *
     * <p>지연이 없으면 모든 Check 가 구독 즉시 끝나 버려서 "직렬인가 병렬인가" 도 "완료
     * 순서가 뒤집혀도 응답 순서가 지켜지는가" 도 관찰할 수 없다. 관찰하려는 성질이 실제로
     * 드러나려면 응답이 겹쳐야 한다.
     */
    private Function<RelationTuple, Duration> delayBy = tuple -> Duration.ZERO;

    public void failFor(Predicate<RelationTuple> failWhen) {
        this.failWhen = failWhen;
    }

    public void delayBy(Function<RelationTuple, Duration> delayBy) {
        this.delayBy = delayBy;
    }

    @Override
    public Mono<Boolean> check(RelationTuple tuple) {
        Mono<Boolean> answer = Mono.fromCallable(() -> {
            checked.add(tuple);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            if (failWhen.test(tuple)) {
                inFlight.decrementAndGet();
                throw new IllegalStateException("Check 실패(테스트)");
            }
            return allowed.contains(tuple);
        });
        Duration delay = delayBy.apply(tuple);
        if (!delay.isZero()) {
            answer = answer.delayElement(delay);
        }
        // 결과를 내려보내기 <b>전에</b> 센다. doFinally 로 세면 안 된다 — 그쪽은 종료 신호를
        // 먼저 전파하고 나서 콜백을 돌리므로, 완전히 직렬인 concatMap 에서도 다음 호출이
        // 시작된 뒤에 감소가 일어나 동시 개수가 2 로 보인다.
        return answer.map(result -> {
            inFlight.decrementAndGet();
            return result;
        });
    }
}

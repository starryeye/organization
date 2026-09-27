package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import org.springframework.beans.factory.config.BeanPostProcessor;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenFGA Check 로 물은 튜플 수를 센다 (조직 멤버 PATCH 설계 §8.5). 멤버 한 명을 바꾸는 요청이 조직 전원을 Check 하지 않는다는 것을
 * 튜플 수로 단정하기 위한 계측이다. {@code @Import} 로 테스트 컨텍스트에만 들어간다.
 */
class TupleCheckCounter implements BeanPostProcessor {

    final AtomicLong checkedTuples = new AtomicLong();

    void reset() {
        checkedTuples.set(0);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof RelationTupleChecker checker)) {
            return bean;
        }
        return new RelationTupleChecker() {
            @Override
            public Mono<Boolean> check(RelationTuple tuple) {
                checkedTuples.incrementAndGet();
                return checker.check(tuple);
            }

            @Override
            public Mono<Set<RelationTuple>> existing(Set<RelationTuple> candidates) {
                checkedTuples.addAndGet(candidates.size());
                return checker.existing(candidates);
            }
        };
    }
}

package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import reactor.core.publisher.Mono;

/**
 * 계산된 델타를 인가 시스템에 반영한다. 읽기 메서드는 의도적으로 두지 않는다.
 */
public interface RelationTupleWriter {

    Mono<TupleWriteResult> apply(TupleDelta delta);

    /** rebuild(store 모드) 전용. store 를 지우고 같은 이름으로 다시 만든 뒤 인가 모델을 등록한다. */
    Mono<Void> resetStore();
}

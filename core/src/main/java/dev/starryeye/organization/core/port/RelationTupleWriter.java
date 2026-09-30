package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import reactor.core.publisher.Mono;

/**
 * 계산된 델타를 인가 시스템에 반영한다. 장부를 읽지 않는다 — 판정은 {@link RelationTupleChecker}, 재적재의 장부 훑기는
 * {@link RelationTupleScanner} 다.
 *
 * <p>장부(store)를 지우거나 다시 만드는 메서드는 없다 — 장부 번호는 한 번 만들면 바뀌지 않는다(설계 2026-09-29 §2).
 */
public interface RelationTupleWriter {

    Mono<TupleWriteResult> apply(TupleDelta delta);
}

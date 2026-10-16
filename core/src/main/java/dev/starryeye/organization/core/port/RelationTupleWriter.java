package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * 계산된 델타를 인가 시스템에 반영한다. 장부를 읽지 않는다 — 판정은 {@link RelationTupleChecker}, 재적재의 장부 훑기는
 * {@link RelationTupleScanner} 다.
 *
 * <p>장부(store)를 지우거나 다시 만드는 메서드는 없다 — 장부 번호는 한 번 만들면 바뀌지 않는다(설계 2026-09-29 §2).
 */
public interface RelationTupleWriter {

    /** 알림 없이 반영한다. */
    default Mono<TupleWriteResult> apply(TupleDelta delta) {
        return apply(delta, 묶음 -> {
        });
    }

    /**
     * 델타를 반영하되, 쓰기 묶음 하나가 끝날 때마다 그 묶음의 결과(실제로 쓴 줄·지운 줄·실패)로 {@code 묶음마다} 를 부른다(설계 2026-10-15 §4). 차단기로
     * 멈추거나({@link TupleWriteAbortedException}) 도중에 취소돼도 끝난 묶음은 이미 알린 뒤다 — 실패한 회차·요청의 권한 변경도 이력에 남는다. 쓰고 응답을
     * 잃은 묶음은 반영됐는지 모르므로 알리지 않는다(§8). 여러 스레드에서 부를 수 있다.
     */
    Mono<TupleWriteResult> apply(TupleDelta delta, Consumer<TupleWriteResult> 묶음마다);
}

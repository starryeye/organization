package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenFGA 에 실제로 반영된 튜플의 기록.
 *
 * <p>OpenFGA 의 <b>열거</b> API(Read/ListObjects)를 쓰지 않으므로 이것이 OpenFGA 상태를
 * 대신하는 유일한 기록이다. {@code Check} 는 허용되지만 점 조회라 열거를 대체하지 못한다 —
 * "kim 이 개발본부의 member 인가"에는 답해도 "지금 어떤 튜플들이 있나"에는 답하지 못하므로,
 * diff 의 기준선은 여전히 이 기록에서 와야 한다.
 */
public interface TupleSnapshotRepository {

    /** 없으면 빈 Mono */
    Mono<TupleSnapshot> findLatest();

    /** 튜플 → 메타 → 포인터 순으로 저장한다. 포인터를 마지막에 갱신해야 중간 실패가 안전하다. */
    Mono<Void> save(TupleSnapshot snapshot);

    Flux<SnapshotMeta> listRecent(int days);

    Mono<TupleSnapshot> findById(String snapshotId);

    /** rebuild 전용. 모든 스냅샷과 포인터를 지운다. */
    Mono<Void> reset();

    /** DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 명시적으로 정리한다. 삭제한 스냅샷 수를 반환한다. */
    Mono<Integer> purgeExpired();
}

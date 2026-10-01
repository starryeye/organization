package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenFGA 에 실제로 반영된 튜플의 기록.
 *
 * <p>평소 동기화는 OpenFGA 의 열거 API(Read/ListObjects)를 쓰지 않으므로 이것이 OpenFGA 상태를
 * 대신하는 유일한 기록이다. {@code Check} 는 허용되지만 점 조회라 열거를 대체하지 못한다 —
 * "kim 이 개발본부의 member 인가"에는 답해도 "지금 어떤 튜플들이 있나"에는 답하지 못하므로,
 * diff 의 기준선은 여전히 이 기록에서 와야 한다.
 */
public interface TupleSnapshotRepository {

    /**
     * 포인터가 없거나 "기록 중" 표시만 있으면 빈 Mono(처음 설치). 포인터가 가리키는 스냅샷의 메타가 없거나 튜플 수가 메타와 다르면
     * {@link SnapshotIntegrityException} — 빈 기준선으로 넘어가면 삭제를 조용히 놓친다.
     */
    Mono<TupleSnapshot> findLatest();

    /** 메타 → 튜플 → 포인터 순으로 저장한다. 메타가 먼저라 중간에 죽어도 정리 대상이고, 포인터가 마지막이라 반쪽이 기준선이 되지 않는다. */
    Mono<Void> save(TupleSnapshot snapshot);

    Flux<SnapshotMeta> listRecent(int days);

    /** 메타가 없으면 빈 Mono. 튜플 수가 메타와 다르면 {@link SnapshotIntegrityException}. */
    Mono<TupleSnapshot> findById(String snapshotId);

    /**
     * "기록 중" 표시를 남긴다 — LDAP 이 OpenFGA 에 쓰기 <b>직전</b>에 부른다(설계 2026-09-30 §4.1). 다음 {@link #save} 가 포인터를 새로 쓰면서
     * 표시가 사라진다. 그래서 표시가 남아 있다는 것은 "쓰기 시작했는데 기록을 끝내지 못했다"는 뜻이다. 포인터가 없어도(첫 설치) 표시만 남긴다.
     */
    Mono<Void> markWriting();

    /** "기록 중" 표시가 남아 있는가 — 지난 회차가 쓰기 시작한 뒤 기록 전에 멈췄다. 강한 일관성으로 읽는다. */
    Mono<Boolean> isWriting();

    /**
     * 보존 기간이 지난 스냅샷을 지운다. 최신 포인터가 가리키는 스냅샷은 기간과 상관없이 지우지 않는다 — 비교 기준이다.
     * 스냅샷은 테이블 TTL 을 쓰지 않으므로 이 정리가 유일한 삭제 경로다. 삭제한 스냅샷 수를 반환한다.
     */
    Mono<Integer> purgeExpired();
}

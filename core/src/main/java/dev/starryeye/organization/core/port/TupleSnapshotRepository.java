package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.RevocationMarks;
import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

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
     * 포인터가 없거나 "기록 중" 표시만 있으면 빈 Mono(처음 설치). 포인터가 가리키는 스냅샷의 메타가 없거나 본문을 온전히 읽지 못하면(빠진 조각, 풀 수 없는 본문, 메타와 다른 튜플 수 등)
     * {@link SnapshotIntegrityException} — 빈 기준선으로 넘어가면 삭제를 조용히 놓친다. 불확실 줄(U)도 본문과 같은 검사를 받는다.
     * 가져온 아이템을 푸는 동안의 실패는 모두 {@link SnapshotIntegrityException} 이고, 가져오는 중의 실패(저장소·SDK 오류)는 절대 이 예외로 보고하지 않는다 —
     * 기록 중 회차와 재적재는 이 예외일 때만 재활성 가드를 건너뛰고 그 밖의 오류는 회차를 실패시킨다(설계 2026-11-25 E1·E3).
     */
    default Mono<TupleSnapshot> findLatest() {
        return findLatestMarked().map(MarkedSnapshot::snapshot);
    }

    /**
     * {@link #findLatest} 와 같은 계약이고, 즉시 회수 표시를 함께 준다. 표시는 스냅샷 번호를 알려 준 같은 포인터 읽기에서 온다 —
     * 번호와 표시가 서로 다른 읽기에서 오면 어긋난다(설계 2026-11-28 V14).
     */
    Mono<MarkedSnapshot> findLatestMarked();

    /** 최신 스냅샷과 그 포인터 줄의 즉시 회수 표시(설계 2026-11-28 V14). */
    record MarkedSnapshot(TupleSnapshot snapshot, RevocationMarks marks) {
    }

    /**
     * 메타 → 본문 → 불확실 줄(U) → 포인터 순으로 저장한다. 메타가 먼저라 중간에 죽어도 정리 대상이고, 포인터가 마지막이라 반쪽이 기준선이
     * 되지 않는다. U 가 비어 있으면 U 는 쓰지 않는다.
     */
    Mono<Void> save(TupleSnapshot snapshot);

    Flux<SnapshotMeta> listRecent(int days);

    /**
     * 메타가 없으면 빈 Mono. 본문을 온전히 읽지 못하면 {@link SnapshotIntegrityException}. 가져온 아이템을 푸는 실패만 이 예외이고
     * 가져오는 중의 실패는 아니다({@link #findLatest} 와 같은 계약, 설계 2026-11-25 E1·E3).
     */
    Mono<TupleSnapshot> findById(String snapshotId);

    /**
     * "기록 중" 표시를 남긴다 — LDAP 이 OpenFGA 에 쓰기 <b>직전</b>에 부른다(설계 2026-09-30 §4.1). 다음 {@link #save} 가 포인터를 새로 쓰면서
     * 표시가 사라진다. 그래서 표시가 남아 있다는 것은 "쓰기 시작했는데 기록을 끝내지 못했다"는 뜻이다. 포인터가 없어도(첫 설치) 표시만 남긴다.
     */
    Mono<Void> markWriting();

    /**
     * 기준선(스냅샷 포인터)이 지금 어떤 상태인가(설계 2026-10-20 §4). 강한 일관성으로 읽는다.
     * <ul>
     *   <li>{@link BaselineState#NONE} — 포인터 줄이 없다(첫 설치이거나 새 테이블). 포인터 줄에 "기록 중" 칸도 스냅샷 번호도 없는 경우도 같다.</li>
     *   <li>{@link BaselineState#WRITING} — "기록 중" 표시가 남아 있다. 지난 회차가 쓰기 시작한 뒤 기록 전에 멈췄다. 스냅샷 번호가 있어도 표시가 먼저다.</li>
     *   <li>{@link BaselineState#PRESENT} — 표시 없이 스냅샷 번호가 있다.</li>
     * </ul>
     */
    default Mono<BaselineState> baselineState() {
        return baselinePointer().map(BaselinePointer::state);
    }

    /**
     * 포인터 줄만 강한 일관성으로 읽어 상태와 포인터의 스냅샷 번호를 함께 준다. 본문은 읽지 않는다(설계 2026-11-28 V6·V14).
     * 번호가 없으면 null.
     */
    Mono<BaselinePointer> baselinePointer();

    /** {@link #baselinePointer()} 의 값. {@code snapshotId} 는 포인터의 번호이고 없으면 null. */
    record BaselinePointer(BaselineState state, String snapshotId) {
    }

    /**
     * 즉시 회수가 지우려는 줄을 포인터 줄의 {@code unknown} 칸에 더한다 — OpenFGA 에서 지우기 <b>직전</b>에 부른다(설계 2026-11-28 V6·V9).
     * 조건: 포인터 번호가 {@code snapshotId} 와 같고 "기록 중" 칸이 없다. 아니면 {@link BaselineMovedException}.
     * 더한 뒤 두 칸이 한도({@link RevocationMarks#MAX_BYTES})를 넘으면 {@link RevocationMarksFullException} 이고 아무것도 쓰지 않는다.
     * 빈 집합이면 아무것도 하지 않는다.
     */
    Mono<Void> markRevoking(String snapshotId, Set<RelationTuple> tuples);

    /**
     * 지우기가 끝난 줄을 정리한다 — {@code gone} 은 {@code unknown} 에서 빼서 {@code gone} 칸에 더하고, {@code dropped} 는 {@code unknown} 에서만 뺀다
     * (설계 2026-11-28 V8). 조건은 {@link #markRevoking} 과 같다. 둘 다 비면 아무것도 하지 않는다.
     */
    Mono<Void> settleRevocation(String snapshotId, Set<RelationTuple> gone, Set<RelationTuple> dropped);

    /** {@link #baselineState()} 의 값. */
    enum BaselineState {
        NONE,
        WRITING,
        PRESENT
    }

    /**
     * 보존 기간이 지난 스냅샷을 지운다. 최신 포인터가 가리키는 스냅샷은 기간과 상관없이 지우지 않는다 — 비교 기준이다.
     * 스냅샷은 테이블 TTL 을 쓰지 않으므로 이 정리가 유일한 삭제 경로다. 삭제한 스냅샷 수를 반환한다.
     */
    Mono<Integer> purgeExpired();
}

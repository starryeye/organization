package dev.starryeye.organization.core.model;

import java.time.Instant;
import java.util.Set;

/**
 * OpenFGA 에 반영된 튜플의 기록(설계 2026-10-20 §3.2). 두 집합을 갖는다.
 *
 * <ul>
 *   <li>{@code tuples}(B) — 장부에 있다고 확실히 아는 줄.</li>
 *   <li>{@code uncertain}(U) — 보냈는데 결과를 몰라 장부에 있는지 모르는 줄. 다음 회차가 이 줄을 다시 정한다.</li>
 * </ul>
 *
 * <p>세 번째 집합 {@code inactive}(I)는 그 회차 그림에서 <b>비활성 계정의 소속 줄</b>이다(설계 2026-11-24 R1). 다음 회차가 "직전에 막혀 있던
 * 직원"을 알아보는 데 쓴다. B·U 와 달리 장부에 대한 주장이 아니라 그림의 한 단면이라 B 와 겹쳐도 된다 — 겹침 검사는 B·U 사이에만 있다.
 *
 * <p>B 와 U 는 겹치지 않는다 — 겹치지 않게 셈하는 것은 만드는 쪽의 몫이고, 겹친 채로 만들면 생성자가 {@link IllegalArgumentException} 으로 막는다.
 * 둘이 겹치면 기준 수(|B| + |U|)가 부풀고 같은 줄이 확실하면서 불확실하다는 모순이 된다. {@link #meta()} 의 튜플 수는 {@code tuples} 만 센다.
 */
public record TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples, Set<RelationTuple> uncertain,
                            Set<RelationTuple> inactive) {

    public TupleSnapshot {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
        uncertain = uncertain == null ? Set.of() : Set.copyOf(uncertain);
        inactive = inactive == null ? Set.of() : Set.copyOf(inactive);
        // 작은 쪽을 훑어 큰 쪽에 묻는다 — 비용이 작은 집합의 크기에 비례한다
        Set<RelationTuple> smaller = tuples.size() <= uncertain.size() ? tuples : uncertain;
        Set<RelationTuple> larger = smaller == tuples ? uncertain : tuples;
        for (RelationTuple tuple : smaller) {
            if (larger.contains(tuple)) {
                throw new IllegalArgumentException("확실한 줄(B)과 불확실한 줄(U)이 겹친다 — 예: " + tuple);
            }
        }
    }

    /** 비활성 소속 줄(I)이 없는 스냅샷. */
    public TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples, Set<RelationTuple> uncertain) {
        this(id, createdAt, source, tuples, uncertain, Set.of());
    }

    /** 불확실한 줄도 비활성 소속 줄도 없는 스냅샷. */
    public TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples) {
        this(id, createdAt, source, tuples, Set.of());
    }

    public SnapshotMeta meta() {
        return new SnapshotMeta(id, createdAt, source, tuples.size());
    }
}

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
 * <p>둘은 겹치지 않는다 — 겹치지 않게 셈하는 것은 만드는 쪽의 몫이고, 겹친 채로 만들면 생성자가 {@link IllegalArgumentException} 으로 막는다.
 * 둘이 겹치면 기준 수(|B| + |U|)가 부풀고 같은 줄이 확실하면서 불확실하다는 모순이 된다. {@link #meta()} 의 튜플 수는 {@code tuples} 만 센다.
 */
public record TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples, Set<RelationTuple> uncertain) {

    public TupleSnapshot {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
        uncertain = uncertain == null ? Set.of() : Set.copyOf(uncertain);
        // 작은 쪽을 훑어 큰 쪽에 묻는다 — 비용이 작은 집합의 크기에 비례한다
        Set<RelationTuple> smaller = tuples.size() <= uncertain.size() ? tuples : uncertain;
        Set<RelationTuple> larger = smaller == tuples ? uncertain : tuples;
        for (RelationTuple tuple : smaller) {
            if (larger.contains(tuple)) {
                throw new IllegalArgumentException("확실한 줄(B)과 불확실한 줄(U)이 겹친다 — 예: " + tuple);
            }
        }
    }

    /** 불확실한 줄이 없는 스냅샷. */
    public TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples) {
        this(id, createdAt, source, tuples, Set.of());
    }

    public SnapshotMeta meta() {
        return new SnapshotMeta(id, createdAt, source, tuples.size());
    }
}

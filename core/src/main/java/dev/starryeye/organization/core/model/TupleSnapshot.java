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
 * <p>둘은 겹치지 않는다 — 겹치지 않게 셈하는 것은 만드는 쪽의 몫이다. {@link #meta()} 의 튜플 수는 {@code tuples} 만 센다.
 */
public record TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples, Set<RelationTuple> uncertain) {

    public TupleSnapshot {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
        uncertain = uncertain == null ? Set.of() : Set.copyOf(uncertain);
    }

    /** 불확실한 줄이 없는 스냅샷. */
    public TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples) {
        this(id, createdAt, source, tuples, Set.of());
    }

    public SnapshotMeta meta() {
        return new SnapshotMeta(id, createdAt, source, tuples.size());
    }
}

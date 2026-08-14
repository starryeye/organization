package dev.starryeye.organization.core.model;

import java.time.Instant;
import java.util.Set;

public record TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples) {

    public TupleSnapshot {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
    }

    public SnapshotMeta meta() {
        return new SnapshotMeta(id, createdAt, source, tuples.size());
    }
}

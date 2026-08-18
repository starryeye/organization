package dev.starryeye.organization.core.model;

import java.util.Set;

public record TupleDelta(Set<RelationTuple> toWrite, Set<RelationTuple> toDelete) {

    public TupleDelta {
        toWrite = toWrite == null ? Set.of() : Set.copyOf(toWrite);
        toDelete = toDelete == null ? Set.of() : Set.copyOf(toDelete);
    }

    public static TupleDelta empty() {
        return new TupleDelta(Set.of(), Set.of());
    }

    public static TupleDelta writeOnly(Set<RelationTuple> tuples) {
        return new TupleDelta(tuples, Set.of());
    }

    public static TupleDelta deleteOnly(Set<RelationTuple> tuples) {
        return new TupleDelta(Set.of(), tuples);
    }

    public boolean isEmpty() {
        return toWrite.isEmpty() && toDelete.isEmpty();
    }
}

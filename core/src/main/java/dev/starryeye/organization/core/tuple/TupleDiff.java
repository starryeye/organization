package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;

import java.util.HashSet;
import java.util.Set;

public final class TupleDiff {

    private TupleDiff() {
    }

    /**
     * @param baseline 직전 스냅샷. OpenFGA 에 반영되어 있다고 믿는 상태
     * @param target   이번에 읽어온 목표 상태
     */
    public static TupleDelta between(Set<RelationTuple> baseline, Set<RelationTuple> target) {
        Set<RelationTuple> safeBaseline = baseline == null ? Set.of() : baseline;
        Set<RelationTuple> safeTarget = target == null ? Set.of() : target;

        Set<RelationTuple> toWrite = new HashSet<>(safeTarget);
        toWrite.removeAll(safeBaseline);

        Set<RelationTuple> toDelete = new HashSet<>(safeBaseline);
        toDelete.removeAll(safeTarget);

        return new TupleDelta(toWrite, toDelete);
    }
}

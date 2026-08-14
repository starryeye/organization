package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.RelationTuple;

import java.util.List;
import java.util.Set;

public record TupleMappingResult(Set<RelationTuple> tuples, List<String> warnings) {

    public TupleMappingResult {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

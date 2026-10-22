package dev.starryeye.organization.core.model;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OpenFGA 에 <b>실제로 반영된</b> 튜플만 담는다.
 * 이 결과로 새 스냅샷을 계산하기 때문에, 실패한 튜플은 다음 동기화의 diff 가 다시 잡는다.
 * 실패는 확정(반영되지 않았음을 안다)과 불확실(보냈는데 결과를 모른다)로 갈린다 — {@link TupleFailure}.
 */
public record TupleWriteResult(
        Set<RelationTuple> written,
        Set<RelationTuple> deleted,
        List<TupleFailure> failures
) {

    public TupleWriteResult {
        written = written == null ? Set.of() : Set.copyOf(written);
        deleted = deleted == null ? Set.of() : Set.copyOf(deleted);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static TupleWriteResult empty() {
        return new TupleWriteResult(Set.of(), Set.of(), List.of());
    }

    public boolean hasFailure() {
        return !failures.isEmpty();
    }

    /** 실패 가운데 불확실한 줄 — 보냈는데 결과를 모른다(설계 2026-10-20 §3.1). 확정 실패는 들어가지 않는다 */
    public Set<RelationTuple> uncertain() {
        return failures.stream()
                .filter(TupleFailure::uncertain)
                .map(TupleFailure::tuple)
                .collect(Collectors.toUnmodifiableSet());
    }
}

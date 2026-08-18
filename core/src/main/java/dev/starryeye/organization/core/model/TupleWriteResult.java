package dev.starryeye.organization.core.model;

import java.util.List;
import java.util.Set;

/**
 * OpenFGA 에 <b>실제로 반영된</b> 튜플만 담는다.
 * 이 결과로 새 스냅샷을 계산하기 때문에, 실패한 튜플은 다음 동기화의 diff 가 다시 잡는다.
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
}

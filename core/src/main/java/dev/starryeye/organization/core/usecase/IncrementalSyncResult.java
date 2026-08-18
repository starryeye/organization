package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.TupleWriteResult;

/**
 * @param fullyApplied 의도한 델타가 전부 반영됐는지. false 면 SCIM 응답은 5xx 여야 하며,
 *                     IdP 가 재시도해 나머지를 반영하게 한다.
 */
public record IncrementalSyncResult(boolean fullyApplied, TupleWriteResult writeResult) {

    public static IncrementalSyncResult noChange() {
        return new IncrementalSyncResult(true, TupleWriteResult.empty());
    }

    public static IncrementalSyncResult of(TupleWriteResult result) {
        return new IncrementalSyncResult(!result.hasFailure(), result);
    }

    public boolean hasFailure() {
        return writeResult.hasFailure();
    }
}

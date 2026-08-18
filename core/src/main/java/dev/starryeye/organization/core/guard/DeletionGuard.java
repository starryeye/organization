package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;

import java.util.Set;

/**
 * LDAP 전체 동기화가 잘못된 결과(필터 오류로 0건 응답, 부분 응답)를 가져왔을 때
 * 전직원 권한이 한 번에 날아가는 것을 막는다.
 *
 * <p>SCIM 의 의도된 단건 삭제와 rebuild 의 의도된 전체 삭제에는 적용하지 않는다.
 */
public class DeletionGuard {

    private final DeletionGuardPolicy policy;

    public DeletionGuard(DeletionGuardPolicy policy) {
        this.policy = policy;
    }

    public GuardDecision evaluate(TupleDelta delta, Set<RelationTuple> baseline) {
        if (!policy.enabled()) {
            return GuardDecision.proceed();
        }
        int baselineSize = baseline == null ? 0 : baseline.size();
        if (baselineSize < policy.minBaseline()) {
            return GuardDecision.proceed();
        }

        int deleteCount = delta.toDelete().size();
        double ratio = (double) deleteCount / baselineSize;
        if (ratio <= policy.thresholdRatio()) {
            return GuardDecision.proceed();
        }

        return GuardDecision.abort(
                "삭제 대상 %d건(기준 스냅샷 %d건의 %.1f%%)이 임계치 %.1f%%를 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요"
                        .formatted(deleteCount, baselineSize, ratio * 100, policy.thresholdRatio() * 100));
    }
}

package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;

import java.util.Set;

/**
 * LDAP 전체 동기화가 잘못된 결과(필터 오류로 0건 응답, 부분 응답)를 가져왔을 때
 * 전직원 권한이 한 번에 날아가는 것을 막는다.
 *
 * <p>LDAP 의 스냅샷 비교, 멈춘 뒤 훑어 맞추기, 재적재에 적용한다(설계 2026-09-30 §4.3) — {@code FORCED}·{@code force} 로만
 * 건너뛴다. SCIM 의 의도된 단건 삭제에는 적용하지 않는다.
 */
public class DeletionGuard {

    private final DeletionGuardPolicy policy;

    public DeletionGuard(DeletionGuardPolicy policy) {
        this.policy = policy;
    }

    public GuardDecision evaluate(TupleDelta delta, Set<RelationTuple> baseline) {
        return evaluate(delta.toDelete().size(), baseline == null ? 0 : baseline.size(), "기준 스냅샷");
    }

    /**
     * 지울 수와 기준 수로 판정한다 — 장부를 훑어 맞출 때는 기준이 스냅샷이 아니라 훑은 장부다(설계 2026-09-30 §4.3).
     *
     * @param baselineLabel 메시지에 쓸 기준의 이름("기준 스냅샷", "훑은 장부")
     */
    public GuardDecision evaluate(int deleteCount, int baselineCount, String baselineLabel) {
        if (!policy.enabled()) {
            return GuardDecision.proceed();
        }
        if (baselineCount < policy.minBaseline()) {
            return GuardDecision.proceed();
        }

        double ratio = (double) deleteCount / baselineCount;
        if (ratio <= policy.thresholdRatio()) {
            return GuardDecision.proceed();
        }

        return GuardDecision.abort(
                "삭제 대상 %d건(%s %d건의 %.1f%%)이 임계치 %.1f%%를 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요"
                        .formatted(deleteCount, baselineLabel, baselineCount, ratio * 100, policy.thresholdRatio() * 100));
    }
}

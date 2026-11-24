package dev.starryeye.organization.core.guard;

/**
 * 재활성 가드의 정책 — 삭제 가드 정책과 같은 모양이다(설계 2026-11-24 R3).
 *
 * @param thresholdRatio   재활성 비율 허용치. 이 값을 <b>초과</b>하면 보류한다
 * @param minBaseline      기준 수(직전 비활성 소속 직원 수)가 이 값 미만이면 비율은 보지 않는다
 * @param maxReactivations 한 회차에 재활성할 직원 수의 상한. 이 값을 <b>초과</b>하면 비율과 상관없이 보류한다. 0 이하면 보지 않는다
 */
public record ReactivationGuardPolicy(boolean enabled, double thresholdRatio, int minBaseline, int maxReactivations) {

    public static ReactivationGuardPolicy defaults() {
        return new ReactivationGuardPolicy(true, 0.3, 10, 100);
    }
}

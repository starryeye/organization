package dev.starryeye.organization.core.guard;

/**
 * @param thresholdRatio 삭제 허용 비율. 이 값을 <b>초과</b>하면 중단한다
 * @param minBaseline    기준 스냅샷이 이 크기 미만이면 비율이 무의미하므로 비율은 보지 않는다
 * @param maxDeletes     한 회차에 지울 줄 수의 상한. 이 값을 <b>초과</b>하면 비율과 상관없이 중단한다. 0 이하면 보지 않는다.
 *                       min-baseline 과 상관없이 본다(설계 2026-11-13 G1~G3)
 */
public record DeletionGuardPolicy(boolean enabled, double thresholdRatio, int minBaseline, int maxDeletes) {

    public static DeletionGuardPolicy defaults() {
        return new DeletionGuardPolicy(true, 0.3, 10, 5000);
    }
}

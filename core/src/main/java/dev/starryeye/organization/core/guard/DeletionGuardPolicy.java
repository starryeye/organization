package dev.starryeye.organization.core.guard;

/**
 * @param thresholdRatio 삭제 허용 비율. 이 값을 <b>초과</b>하면 중단한다
 * @param minBaseline    기준 스냅샷이 이 크기 미만이면 비율이 무의미하므로 가드를 적용하지 않는다
 */
public record DeletionGuardPolicy(boolean enabled, double thresholdRatio, int minBaseline) {

    public static DeletionGuardPolicy defaults() {
        return new DeletionGuardPolicy(true, 0.3, 10);
    }
}

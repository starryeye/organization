package dev.starryeye.organization.core.model;

import java.util.List;
import java.util.Set;

/**
 * 재활성 가드가 보류한 직원의 내역 — 운영자가 "정말 다시 켠 것인가"를 숫자와 예로 확인하게 한다(설계 2026-11-24 R7).
 *
 * @param count 보류한 직원 수(서로 다른 id)
 * @param ids   사전순 앞 {@link GuardHold#SAMPLE_SIZE} 개
 */
public record ReactivationHold(int count, List<String> ids) {

    public ReactivationHold {
        ids = List.copyOf(ids);
    }

    public static ReactivationHold of(Set<String> userIds) {
        return new ReactivationHold(userIds.size(),
                userIds.stream().sorted().limit(GuardHold.SAMPLE_SIZE).toList());
    }
}

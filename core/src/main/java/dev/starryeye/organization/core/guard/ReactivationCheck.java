package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.ReactivationHold;

/**
 * 재활성 판정의 결과(설계 2026-11-24 §3.2).
 *
 * <p>{@link #held()} 가 거짓이면 {@code picture} 는 받은 그림 그대로이고 {@code hold}·{@code message} 는 {@code null} 이다.
 * 참이면 {@code picture} 는 재활성 직원을 비활성으로 되돌린 그림′ 이다(R4).
 */
public record ReactivationCheck(DirectorySnapshot picture, ReactivationHold hold, String message) {

    public boolean held() {
        return hold != null;
    }

    public static ReactivationCheck pass(DirectorySnapshot picture) {
        return new ReactivationCheck(picture, null, null);
    }
}

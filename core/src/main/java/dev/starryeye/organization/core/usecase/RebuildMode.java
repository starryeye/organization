package dev.starryeye.organization.core.usecase;

import java.util.Locale;

public enum RebuildMode {

    /** 직전 스냅샷을 근거로 전부 지운 뒤 재적재. 안전하지만 스냅샷에 없는 튜플은 남는다 */
    SNAPSHOT,

    /** store 자체를 재생성. 진짜로 깨끗해지지만 재적재까지 인가 질의가 실패하는 공백이 생긴다 */
    STORE;

    public static RebuildMode from(String raw) {
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "알 수 없는 rebuild 모드입니다: " + raw + " (snapshot 또는 store)");
        }
    }
}

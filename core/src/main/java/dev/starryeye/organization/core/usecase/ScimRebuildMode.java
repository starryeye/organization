package dev.starryeye.organization.core.usecase;

import java.util.Locale;

/**
 * SCIM 재적재가 무엇까지 지우는가.
 *
 * <p>LDAP 의 {@link RebuildMode}(snapshot/store)와 축이 다르다. 그쪽은 <b>OpenFGA 를 얼마나
 * 지울지</b>를 고르고 DynamoDB 는 언제나 LDAP 에서 다시 읽어 덮어쓴다. SCIM 은 push 모델이라
 * "다시 읽어온다"에 해당하는 동작이 없어서, 대신 <b>DynamoDB 까지 지울지</b>를 고른다.
 */
public enum ScimRebuildMode {

    /**
     * OpenFGA 만 비우고 현재상태(DynamoDB)가 요구하는 튜플을 전부 다시 쓴다.
     * 상태는 건드리지 않는다 — SCIM 에서는 그것이 곧 진실이기 때문이다.
     */
    TUPLES,

    /**
     * OpenFGA 를 비우고 <b>현재상태의 직원·조직까지 전부 지운다.</b> 튜플은 쓰지 않는다 —
     * 상태가 비었으니 요구되는 튜플도 없다.
     *
     * <p><b>되돌릴 수 없다.</b> SCIM 배포에서 DynamoDB 는 조직도의 유일한 사본이고, 스냅샷에는
     * 튜플의 식별자만 있어 이름·이메일·재직 여부를 복원할 수 없다. 실행 뒤에는 반드시
     * <b>IdP 콘솔에서 전체 재프로비저닝</b>을 걸어야 조직도가 돌아온다 — 그 절차는 이 API 밖에 있다.
     */
    WIPE;

    public static ScimRebuildMode from(String raw) {
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "알 수 없는 rebuild 모드입니다: " + raw + " (tuples 또는 wipe)");
        }
    }
}

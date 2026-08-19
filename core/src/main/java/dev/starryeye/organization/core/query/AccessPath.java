package dev.starryeye.organization.core.query;

/**
 * 한 직원이 한 조직에 접근되는(또는 되어야 하는) 경로 한 줄.
 *
 * @param shouldHaveAccess 현재상태(DynamoDB)가 요구하는 값
 * @param openFgaCheck     OpenFGA 의 실제 판정. Check 호출이 실패하면 null
 * @param cycle            상위 순회 중 이미 본 조직에 다시 닿았는지
 */
public record AccessPath(String orgCode, String displayName, String via,
                         boolean shouldHaveAccess, Boolean openFgaCheck, boolean cycle) {

    /** 직원이 이 조직의 멤버로 직접 등록돼 있다. */
    public static final String DIRECT = "direct";
    /** 하위 조직을 통해 상위로 올라온 경로다. */
    public static final String ROLLUP = "rollup";

    /** 파생값과 실제 판정이 갈리는가. Check 를 못 했으면 판단을 보류한다(false). */
    public boolean drifted() {
        return openFgaCheck != null && shouldHaveAccess != openFgaCheck;
    }
}

package dev.starryeye.organization.core.usecase;

/**
 * 관리 API 의 지정 요청을 거절한다 — 아무것도 바꾸지 않았다(설계 2026-11-04 H5, §3.1). 판정은 락 안에서 한다. 호출자는 상위 조직이 없으면 404, 순환이면
 * 409 로 옮긴다. 순환은 "이 요청 뒤에도 그 상위가 순환 때문에 반영되지 않는다" 이다 — 자기 자신, 상위가 이 조직의 하위, 같은 상위의 지정이 보류 중인 경우다.
 */
public final class HierarchyRejectedException extends RuntimeException {

    public enum Reason { PARENT_NOT_FOUND, CYCLE }

    private final Reason reason;
    private final String orgCode;
    private final String parentOrgCode;

    public HierarchyRejectedException(Reason reason, String orgCode, String parentOrgCode) {
        super(switch (reason) {
            case PARENT_NOT_FOUND -> "상위 조직이 없다: " + parentOrgCode;
            case CYCLE -> parentOrgCode + " 를 " + orgCode + " 의 상위로 두면 순환이 된다";
        });
        this.reason = reason;
        this.orgCode = orgCode;
        this.parentOrgCode = parentOrgCode;
    }

    public Reason reason() {
        return reason;
    }

    public String orgCode() {
        return orgCode;
    }

    public String parentOrgCode() {
        return parentOrgCode;
    }
}

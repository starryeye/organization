package dev.starryeye.organization.core.usecase;

/**
 * 조직 쪽 SCIM 쓰기가 <b>원천 없는 부여 줄</b>을 남겼을 수 있다고 알린다 — {@code scim.writes.unsourced}(설계 2026-11-11 U5).
 *
 * <p>{@code core} 가 Micrometer 를 알지 않게 하려고 콜백으로 받는다 — {@link RevocationObserver} 와 같은 이유, 같은 모양이다. 요청 하나에 많아야
 * 한 번 부른다. 관찰자가 던져도 요청의 결과는 그대로다 — 부르는 쪽이 경고만 남기고 삼킨다.
 */
public interface UnsourcedGrantObserver {

    /** 이 요청이 원천 없는 부여 줄을 남겼을 수 있다. */
    void leftUnsourced();

    UnsourcedGrantObserver NOOP = () -> {
    };
}

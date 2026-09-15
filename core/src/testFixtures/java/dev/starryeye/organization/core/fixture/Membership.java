package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.MemberRef;

import java.util.Objects;

/**
 * 조직 {@code 조직} 의 멤버 목록에 {@code 멤버} 가 있다는 사실 하나.
 *
 * <p>{@link OrgChart#지워진멤버십()} 이 이것을 모은다. 튜플이 아니라 멤버십으로 기억하는 이유는
 * 튜플로 바꾸는 규칙이 {@link ChartExpectation} 의 것이기 때문이다 — 조직도는 사실만 든다.
 */
public record Membership(String 조직, MemberRef 멤버) {

    public Membership {
        Objects.requireNonNull(조직, "조직");
        Objects.requireNonNull(멤버, "멤버");
    }
}

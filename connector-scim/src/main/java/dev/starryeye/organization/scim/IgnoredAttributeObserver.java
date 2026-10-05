package dev.starryeye.organization.scim;

import java.util.Set;

/**
 * 직원 PATCH 가 받아서 버린 속성을 알린다(설계 2026-10-06 §3.3). 이름은 {@link ScimRfcAttributes} 의 정규 이름이거나 {@code other} 다 —
 * 요청 문자열이 아니라서 메트릭 태그로 써도 수가 늘 유한하다.
 */
@FunctionalInterface
public interface IgnoredAttributeObserver {

    IgnoredAttributeObserver NOOP = names -> { };

    /** 한 요청에서 버린 속성 이름들. 버린 것이 없으면 부르지 않는다. */
    void ignored(Set<String> names);
}

package dev.starryeye.organization.core.audit;

/** 바뀐 속성 하나 — 이전 값과 이후 값. 없던 값은 null 이다(생성이면 이전이, 삭제면 이후가 모두 null). */
public record AttributeChange(String name, String before, String after) {
}

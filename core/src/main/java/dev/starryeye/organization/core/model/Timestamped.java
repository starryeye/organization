package dev.starryeye.organization.core.model;

import java.util.Objects;

/**
 * 도메인 값과 그 저장본의 시각. 도메인 레코드에 시각을 넣지 않는다 — 넣으면 동등 비교·쓰기 판단·조직도 비교가 시각까지 보게 된다
 * (설계 2026-10-09 §4.1, §9). SCIM 응답을 만드는 읽기만 이 모양을 쓴다.
 */
public record Timestamped<T>(T value, ResourceTimes times) {

    public Timestamped {
        Objects.requireNonNull(value, "value");
        times = times == null ? ResourceTimes.UNKNOWN : times;
    }
}

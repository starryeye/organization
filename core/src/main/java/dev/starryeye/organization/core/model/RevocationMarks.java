package dev.starryeye.organization.core.model;

import java.util.Set;

/**
 * 한 사람 즉시 회수가 기준선(스냅샷 포인터 줄)에 남기는 표시 두 칸(설계 2026-11-28 V6·V8).
 *
 * <p>{@code gone} 은 OpenFGA 에서 지운 줄, {@code unknown} 은 지우려 했으나 결과를 모르는 줄이다.
 * 다음 회차가 {@code B′ = B − gone − unknown}, {@code U′ = (U − gone) ∪ unknown} 으로 기준선을 고쳐 읽는다.
 * null 은 빈 집합으로 받고 {@link Set#copyOf} 로 굳힌다.
 */
public record RevocationMarks(Set<RelationTuple> gone, Set<RelationTuple> unknown) {

    /** 두 칸 원소의 UTF-8 바이트 합 한도(256KB, 설계 V9). 같으면 허용하고 넘으면 거절한다. */
    public static final int MAX_BYTES = 262_144;

    public static final RevocationMarks NONE = new RevocationMarks(Set.of(), Set.of());

    public RevocationMarks {
        gone = gone == null ? Set.of() : Set.copyOf(gone);
        unknown = unknown == null ? Set.of() : Set.copyOf(unknown);
    }

    public boolean isEmpty() {
        return gone.isEmpty() && unknown.isEmpty();
    }
}

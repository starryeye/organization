package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;

import java.util.Set;

/**
 * 삭제 가드의 판정(설계 2026-10-20 §5.2).
 *
 * <ul>
 *   <li><b>진행</b> — 지울 줄 전체가 가드 안이다. 델타 전체를 반영한다.</li>
 *   <li><b>일부 반영</b>({@link #partial()}) — 전체는 가드를 넘었지만, 그 가운데 디렉터리가 막았다고 명시한 회수({@link #revocationsOnly()})만으로는
 *       가드 안이다. 그 지우기만 반영하고 부여·나머지 지우기·현재상태 교체는 보류한다. 가드가 걸린 회차이므로 {@link #aborted()} 도 참이다.</li>
 *   <li><b>전부 보류</b> — 전체가 넘었고, 떼어 낼 회수가 없거나 그것만으로도 넘었다. 아무것도 반영하지 않는다.</li>
 * </ul>
 *
 * @param aborted         가드가 걸렸다 — 전부 보류이거나 일부 반영이다
 * @param message         가드가 걸렸으면 전체 지우기의 초과 문구. 진행이면 {@code null}
 * @param revocationsOnly 일부 반영일 때 반영할 지우기. 아니면 빈 집합
 */
public record GuardDecision(boolean aborted, String message, Set<RelationTuple> revocationsOnly) {

    public GuardDecision {
        revocationsOnly = revocationsOnly == null ? Set.of() : Set.copyOf(revocationsOnly);
    }

    public static GuardDecision proceed() {
        return new GuardDecision(false, null, Set.of());
    }

    public static GuardDecision abort(String message) {
        return new GuardDecision(true, message, Set.of());
    }

    /** 가드가 걸렸지만 이 지우기만은 반영한다. {@code message} 는 전체 지우기의 초과 문구다. */
    public static GuardDecision revokeOnly(Set<RelationTuple> revocations, String message) {
        return new GuardDecision(true, message, revocations);
    }

    /** 일부 반영 — 가드가 걸렸지만 {@link #revocationsOnly()} 는 반영한다. */
    public boolean partial() {
        return !revocationsOnly.isEmpty();
    }
}

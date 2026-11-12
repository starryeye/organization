package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * 조직 쪽 쓰기 한 요청이 <b>원천 없는 부여 줄</b>(저장된 상태가 요구하지 않는데 OpenFGA 에 있을 수 있는 줄)을 남겼는지 따라간다(설계 2026-11-11
 * U4~U6).
 *
 * <p>되지울 줄을 정하고({@link #되지울것}), 커밋에 닿지 못하고 끝난 요청이나 되지우지 못한 요청을 요청마다 한 번만 센다. 묶음 알림과 취소가 다른 스레드에서
 * 오므로 모든 메서드가 {@code synchronized} 다.
 */
@Slf4j
final class UnsourcedGrantTracker {

    private static final UnsourcedGrantTracker 없음 = new UnsourcedGrantTracker(null, UnsourcedGrantObserver.NOOP);

    private final String groupId;
    private final UnsourcedGrantObserver observer;

    private Set<RelationTuple> 있어야했던것 = Set.of();
    private boolean 저장했다;
    private Set<RelationTuple> 저장하지_못한_넣기의_줄 = Set.of();

    private TupleDelta delta = TupleDelta.empty();
    private boolean 쓰기_시작;
    private boolean 쓰기_끝;
    private boolean 커밋_마침;
    private boolean 셌다;
    private final Set<RelationTuple> 알린_written = new HashSet<>();
    private final Set<RelationTuple> 알린_uncertain = new HashSet<>();
    private final Set<RelationTuple> 알린_모든_줄 = new HashSet<>();

    private UnsourcedGrantTracker(String groupId, UnsourcedGrantObserver observer) {
        this.groupId = groupId;
        this.observer = observer;
    }

    /** 조직 쪽 쓰기 한 요청의 추적기. */
    static UnsourcedGrantTracker 조직(String groupId, UnsourcedGrantObserver observer) {
        return new UnsourcedGrantTracker(groupId, observer);
    }

    /** 세지도 남기지도 않는다. {@link #묶음마다} 는 {@code 기록} 을 그대로 돌려준다. */
    static UnsourcedGrantTracker 없음() {
        return 없음;
    }

    private boolean 꺼짐() {
        return groupId == null;
    }

    /** 저장하지 않는 커밋의 기준 — 이 집합에 든 줄은 저장된 상태가 요구한다. */
    synchronized void 요구한다(Set<RelationTuple> 있어야했던것) {
        if (꺼짐()) {
            return;
        }
        this.있어야했던것 = Set.copyOf(있어야했던것);
    }

    /** 저장하는 커밋이 저장 성공 직후 부른다. 이 뒤의 기준은 저장하지 못한 넣기의 줄이다. */
    synchronized void 저장했다(Set<RelationTuple> 저장하지_못한_넣기의_줄) {
        if (꺼짐()) {
            return;
        }
        this.저장했다 = true;
        this.저장하지_못한_넣기의_줄 = Set.copyOf(저장하지_못한_넣기의_줄);
    }

    /** 결과에 실패가 없으면 빈 집합. 아니면 요구하지 않는 (쓴 줄 ∪ 결과를 모르는 줄). */
    synchronized Set<RelationTuple> 되지울것(TupleWriteResult result) {
        if (!result.hasFailure()) {
            return Set.of();
        }
        Set<RelationTuple> 줄들 = new HashSet<>(result.written());
        줄들.addAll(result.uncertain());
        return 요구하지_않는(줄들);
    }

    /** 본 쓰기가 시작됐다고 적고 델타를 쥔다. 돌려준 것은 {@code 기록} 에 먼저 넘기고 모은다. */
    synchronized Consumer<TupleWriteResult> 묶음마다(TupleDelta delta, Consumer<TupleWriteResult> 기록) {
        if (꺼짐()) {
            return 기록;
        }
        this.delta = delta;
        this.쓰기_시작 = true;
        return result -> {
            기록.accept(result);
            모은다(result);
        };
    }

    private synchronized void 모은다(TupleWriteResult result) {
        알린_written.addAll(result.written());
        알린_모든_줄.addAll(result.written());
        알린_모든_줄.addAll(result.deleted());
        for (TupleFailure 실패 : result.failures()) {
            알린_모든_줄.add(실패.tuple());
            if (실패.uncertain()) {
                알린_uncertain.add(실패.tuple());
            }
        }
    }

    /** 본 쓰기가 값이나 오류로 끝났다. */
    synchronized void 쓰기가_끝났다() {
        쓰기_끝 = true;
    }

    /** 조직 쪽 {@code Commit} 이 끝났다. 이 뒤의 끝남·취소는 세지 않는다. */
    synchronized void 커밋을_마쳤다() {
        커밋_마침 = true;
    }

    /** 커밋에 닿지 못하고 오류로 끝났다(설계 U5 a). */
    synchronized void 오류로_끝났다(Throwable error) {
        끝남(error.getClass().getSimpleName(), false);
    }

    /** 커밋에 닿지 못하고 취소됐다(설계 U5 a) — 리스 상실이 이리로 온다. */
    synchronized void 취소됐다() {
        끝남("리스 상실로 취소", true);
    }

    /** 되지우기가 실패했다(설계 U5 b). 세기만 한다 — WARN 은 부르는 쪽이 남긴다. */
    synchronized void 되지우기가_실패했다() {
        올린다();
    }

    private void 끝남(String 사유, boolean 취소) {
        if (꺼짐() || 커밋_마침) {
            return;
        }
        Set<RelationTuple> 남은 = 요구하지_않는(보냈을_수_있는(취소));
        if (남은.isEmpty() || 셌다) {
            return;
        }
        log.warn("조직 '{}' 의 쓰기가 커밋에 닿지 못하고 끝나({}) 원천 없는 줄 {}개가 남았을 수 있다 — 같은 요청이 다시 와 성공했는지 보고, 아니면 재적재(mode=tuples)를 건다",
                groupId, 사유, 남은.size());
        올린다();
    }

    private Set<RelationTuple> 보냈을_수_있는(boolean 취소) {
        Set<RelationTuple> 줄들 = new HashSet<>(알린_written);
        for (RelationTuple 줄 : 알린_uncertain) {
            if (delta.toWrite().contains(줄)) {
                줄들.add(줄);
            }
        }
        if (취소 && 쓰기_시작 && !쓰기_끝) {
            for (RelationTuple 줄 : delta.toWrite()) {
                if (!알린_모든_줄.contains(줄)) {
                    줄들.add(줄);
                }
            }
        }
        return 줄들;
    }

    private Set<RelationTuple> 요구하지_않는(Set<RelationTuple> 줄들) {
        Set<RelationTuple> 남은 = new HashSet<>(줄들);
        if (저장했다) {
            남은.retainAll(저장하지_못한_넣기의_줄);
        } else {
            남은.removeAll(있어야했던것);
        }
        return 남은;
    }

    private void 올린다() {
        if (꺼짐() || 셌다) {
            return;
        }
        셌다 = true;
        try {
            observer.leftUnsourced();
        } catch (RuntimeException e) {
            log.warn("원천 없는 줄 지표를 올리지 못했다 — 요청의 결과는 그대로다", e);
        }
    }
}

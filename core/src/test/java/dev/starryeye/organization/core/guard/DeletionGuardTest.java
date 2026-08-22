package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionGuardTest {

    private static Set<RelationTuple> 튜플들(int count) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        IntStream.range(0, count).forEach(i -> tuples.add(RelationTuple.directMember("user" + i, "DEV002")));
        return tuples;
    }

    private static Set<RelationTuple> 앞에서(Set<RelationTuple> source, int count) {
        return source.stream().limit(count).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("삭제 비율이 임계치 이하면 동기화를 진행한다")
    void 임계치_이하면_진행한다() {
        // given — 기준 100건 중 20건 삭제(20%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 20));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("삭제 비율이 임계치를 넘으면 중단하고 사유를 남긴다")
    void 임계치를_넘으면_중단한다() {
        // given — 기준 100건 중 68건 삭제(68%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 68));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isTrue();
        assertThat(decision.message()).contains("68").contains("30");
    }

    @Test
    @DisplayName("LDAP이 0건을 반환해 전건 삭제가 되면 반드시 중단한다")
    void 전건_삭제는_반드시_중단한다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(baseline);

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isTrue();
    }

    @Test
    @DisplayName("기준 스냅샷이 너무 작으면 비율이 무의미하므로 가드를 적용하지 않는다")
    void 기준이_작으면_가드를_적용하지_않는다() {
        // given — 기준 3건 중 2건 삭제(66%)지만 minBaseline 10 미만
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(3);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 2));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("최초 동기화처럼 기준 스냅샷이 비어 있으면 가드를 적용하지 않는다")
    void 기준이_비면_가드를_적용하지_않는다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var delta = TupleDelta.writeOnly(튜플들(500));

        // when
        var decision = guard.evaluate(delta, Set.of());

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("가드를 비활성화하면 전건 삭제도 그대로 진행한다")
    void 비활성화하면_통과한다() {
        // given
        var guard = new DeletionGuard(new DeletionGuardPolicy(false, 0.3, 10));
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(baseline);

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("임계치와 정확히 같은 비율은 통과시킨다")
    void 임계치와_같으면_통과한다() {
        // given — 기준 100건 중 정확히 30건 삭제(30%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 30));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("기준 스냅샷이 아예 null 이어도 가드가 터지지 않고 진행한다")
    void 기준이_null이어도_진행한다() {
        // given — 첫 동기화에는 스냅샷 자체가 없다. 이 분기를 지금까지 어떤 테스트도 타지 않았고,
        // 여기서 NPE 가 나면 최초 동기화가 통째로 실패한다.
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var delta = TupleDelta.writeOnly(튜플들(500));

        // when
        var decision = guard.evaluate(delta, null);

        // then — 기준선 크기를 0 으로 보고 minBaseline 미만이라 가드를 적용하지 않는다
        assertThat(decision.aborted()).isFalse();
    }
}

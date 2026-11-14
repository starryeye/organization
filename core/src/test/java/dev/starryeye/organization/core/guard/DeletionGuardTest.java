package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;
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

        // when
        var decision = guard.evaluate(앞에서(baseline, 20), Set.of(), baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("삭제 비율이 임계치를 넘으면 중단하고 사유를 남긴다")
    void 임계치를_넘으면_중단한다() {
        // given — 기준 100건 중 68건 삭제(68%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);

        // when
        var decision = guard.evaluate(앞에서(baseline, 68), Set.of(), baseline.size(), "기준 스냅샷");

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

        // when
        var decision = guard.evaluate(baseline, Set.of(), baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isTrue();
    }

    @Test
    @DisplayName("기준 스냅샷이 너무 작으면 비율이 무의미하므로 가드를 적용하지 않는다")
    void 기준이_작으면_가드를_적용하지_않는다() {
        // given — 기준 3건 중 2건 삭제(66%)지만 minBaseline 10 미만
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(3);

        // when
        var decision = guard.evaluate(앞에서(baseline, 2), Set.of(), baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("최초 동기화처럼 기준 스냅샷이 비어 있으면 가드를 적용하지 않는다")
    void 기준이_비면_가드를_적용하지_않는다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());

        // when
        var decision = guard.evaluate(Set.of(), Set.of(), 0, "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("가드를 비활성화하면 전건 삭제도 그대로 진행한다 — 상한도 보지 않는다")
    void 비활성화하면_통과한다() {
        // given
        var guard = new DeletionGuard(new DeletionGuardPolicy(false, 0.3, 10, 5));
        var baseline = 튜플들(100);

        // when
        var decision = guard.evaluate(baseline, Set.of(), baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("임계치와 정확히 같은 비율은 통과시킨다")
    void 임계치와_같으면_통과한다() {
        // given — 기준 100건 중 정확히 30건 삭제(30%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);

        // when
        var decision = guard.evaluate(앞에서(baseline, 30), Set.of(), baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("지울 수와 기준 수로도 판정한다 — 장부를 훑어 맞출 때는 기준이 훑은 장부다")
    void 수로_판정한다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());

        // when
        var 넘음 = guard.evaluate(10, 20, "훑은 장부");
        var 안넘음 = guard.evaluate(2, 20, "훑은 장부");
        var 기준이_작음 = guard.evaluate(5, 9, "훑은 장부");

        // then
        assertThat(넘음.aborted()).isTrue();
        assertThat(넘음.message()).contains("훑은 장부 20건").contains("임계치");
        assertThat(안넘음.aborted()).isFalse();
        assertThat(기준이_작음.aborted()).as("최소 기준(10) 미만이면 판정하지 않는다").isFalse();
    }

    // ---------- 비활성 회수는 먼저 (설계 2026-10-20 §5.2) ----------

    @Test
    @DisplayName("전체 지우기가 가드 안이면 비활성 회수를 떼지 않고 전부 진행한다")
    void 가드_안이면_전부_진행한다() {
        // given — 기준 100건 중 20건 삭제(20%), 그중 10건이 비활성 회수다
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);

        // when
        var decision = guard.evaluate(앞에서(baseline, 20), 앞에서(baseline, 10), baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isFalse();
        assertThat(decision.partial()).isFalse();
        assertThat(decision.revocationsOnly()).isEmpty();
    }

    @Test
    @DisplayName("전체 지우기가 가드를 넘으면 비활성으로 생긴 지우기만 떼어 같은 가드로 다시 보고, 통과하면 그것만 반영하게 한다")
    void 넘으면_비활성_회수만_떼어_다시_본다() {
        // given — 기준 100건, 지울 40건(40%) 중 20건이 비활성 회수(20%), 임계치 30%.
        // 비활성 직원의 소속 줄에는 지울 줄 밖의 것(이미 지운 사람)도 섞여 있다 — 떼어 내는 것은 지울 줄과 겹치는 부분이다
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var 비활성_회수 = 앞에서(baseline, 20);
        Set<RelationTuple> 비활성_소속 = new LinkedHashSet<>(비활성_회수);
        비활성_소속.add(RelationTuple.directMember("gone", "DEV002"));

        // when
        var decision = guard.evaluate(앞에서(baseline, 40), 비활성_소속, baseline.size(), "기준 스냅샷");

        // then — 일부만 반영하는 회차도 가드가 걸린 회차다. 초과 문구는 전체 지우기의 것이다
        assertThat(decision.aborted()).isTrue();
        assertThat(decision.partial()).isTrue();
        assertThat(decision.revocationsOnly()).containsExactlyInAnyOrderElementsOf(비활성_회수);
        assertThat(decision.message()).contains("삭제 대상 40건").contains("기준 스냅샷 100건").contains("임계치");
    }

    @Test
    @DisplayName("비활성 회수만으로도 가드를 넘으면(시계 오류·잘못된 정책으로 대량 비활성) 전부 보류한다")
    void 비활성_회수도_넘으면_전부_보류한다() {
        // given — 기준 100건, 지울 40건 중 35건이 비활성 회수(35%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);

        // when
        var decision = guard.evaluate(앞에서(baseline, 40), 앞에서(baseline, 35), baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isTrue();
        assertThat(decision.partial()).isFalse();
        assertThat(decision.revocationsOnly()).isEmpty();
        assertThat(decision.message()).contains("삭제 대상 40건");
    }

    @Test
    @DisplayName("가드를 넘었는데 비활성으로 생긴 지우기가 없으면 전부 보류한다")
    void 비활성_회수가_없으면_전부_보류한다() {
        // given — 기준 100건, 지울 40건. 비활성 직원의 소속 줄은 지울 줄 밖에만 있다
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);

        // when
        var decision = guard.evaluate(앞에서(baseline, 40), Set.of(RelationTuple.directMember("gone", "DEV002")),
                baseline.size(), "기준 스냅샷");

        // then
        assertThat(decision.aborted()).isTrue();
        assertThat(decision.partial()).isFalse();
        assertThat(decision.revocationsOnly()).isEmpty();
    }

    // ---------- 절대 수 상한 (설계 2026-11-13 G1~G5) ----------

    // 깨뜨릴 변경: 상한 비교를 >= 로 바꾸기, 상한 판정을 지우기
    @Test
    @DisplayName("지울 줄이 상한과 같으면 통과하고, 하나 넘으면 비율이 임계치 아래여도 중단한다")
    void 상한과_같으면_통과하고_하나_넘으면_중단한다() {
        // given — 기준 100건, 상한 5
        var guard = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, 5));
        var baseline = 튜플들(100);
        // when
        var 같음 = guard.evaluate(앞에서(baseline, 5), Set.of(), baseline.size(), "기준 스냅샷");
        var 넘음 = guard.evaluate(앞에서(baseline, 6), Set.of(), baseline.size(), "기준 스냅샷");
        // then
        assertThat(같음.aborted()).isFalse();
        assertThat(넘음.aborted()).isTrue();
        assertThat(넘음.message()).isEqualTo(
                "삭제 대상 6건(기준 스냅샷 100건의 6.0%)이 상한 5건을 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요");
    }

    // 깨뜨릴 변경: 상한 판정을 min-baseline 미만 이른 반환 뒤에 두기
    @Test
    @DisplayName("기준이 최소 기준 미만이어도 상한은 본다 — 비율은 보지 않는다")
    void 기준이_최소_기준_미만이어도_상한은_본다() {
        // given
        var guard = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, 3));
        // when
        var 넘음 = guard.evaluate(4, 9, "훑은 장부");
        var 같음 = guard.evaluate(3, 9, "훑은 장부");
        // then
        assertThat(넘음.aborted()).isTrue();
        assertThat(넘음.message()).contains("훑은 장부 9건").contains("상한 3건").doesNotContain("임계치");
        assertThat(같음.aborted()).isFalse();
    }

    // 깨뜨릴 변경: 상한이 0 이하일 때 "모두 넘음"으로 보기(maxDeletes > 0 조건 빼기)
    @Test
    @DisplayName("상한이 0 이하면 상한을 보지 않는다")
    void 상한이_0_이하면_상한을_보지_않는다() {
        // given
        var 영 = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, 0));
        var 음수 = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, -1));
        // when, then
        assertThat(영.evaluate(20, 100, "기준 스냅샷").aborted()).isFalse();
        assertThat(음수.evaluate(20, 100, "기준 스냅샷").aborted()).isFalse();
    }

    // 깨뜨릴 변경: 문구에서 둘 다 넘은 경우를 한쪽만 적기
    @Test
    @DisplayName("비율과 상한을 모두 넘으면 문구에 둘 다 적는다")
    void 비율과_상한을_모두_넘으면_문구에_둘_다_적는다() {
        // given
        var guard = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, 5));
        var baseline = 튜플들(100);
        // when
        var decision = guard.evaluate(앞에서(baseline, 40), Set.of(), baseline.size(), "기준 스냅샷");
        // then
        assertThat(decision.message()).isEqualTo(
                "삭제 대상 40건(기준 스냅샷 100건의 40.0%)이 임계치 30.0%와 상한 5건을 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요");
    }

    // 깨뜨릴 변경: 비율만 넘은 문구를 바꾸기
    @Test
    @DisplayName("비율만 넘으면 문구가 지금과 같다")
    void 비율만_넘으면_문구가_지금과_같다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        // when
        var decision = guard.evaluate(앞에서(baseline, 68), Set.of(), baseline.size(), "기준 스냅샷");
        // then
        assertThat(decision.message()).isEqualTo(
                "삭제 대상 68건(기준 스냅샷 100건의 68.0%)이 임계치 30.0%를 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요");
    }

    // 깨뜨릴 변경: 비활성 회수 재판정에서 상한을 빼기
    @Test
    @DisplayName("비활성 회수의 재판정도 상한을 본다 — 비활성 부분이 상한을 넘으면 전부 보류한다")
    void 비활성_회수의_재판정도_상한을_본다() {
        // given — 기준 100건, 지울 20건(20%), 비활성 8건(8%) — 둘 다 비율 아래, 상한 5 위
        var guard = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, 5));
        var baseline = 튜플들(100);
        // when
        var decision = guard.evaluate(앞에서(baseline, 20), 앞에서(baseline, 8), baseline.size(), "기준 스냅샷");
        // then
        assertThat(decision.aborted()).isTrue();
        assertThat(decision.partial()).isFalse();
        assertThat(decision.revocationsOnly()).isEmpty();
        assertThat(decision.message()).contains("삭제 대상 20건").contains("상한 5건");
    }

    // 깨뜨릴 변경: 비활성 부분 재판정을 상한 없이 하기, 또는 부분 반영을 없애기
    @Test
    @DisplayName("비활성 부분이 상한 아래면 그것만 반영한다")
    void 비활성_부분이_상한_아래면_그것만_반영한다() {
        // given
        var guard = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, 5));
        var baseline = 튜플들(100);
        // when
        var decision = guard.evaluate(앞에서(baseline, 20), 앞에서(baseline, 4), baseline.size(), "기준 스냅샷");
        // then
        assertThat(decision.aborted()).isTrue();
        assertThat(decision.partial()).isTrue();
        assertThat(decision.revocationsOnly()).containsExactlyInAnyOrderElementsOf(앞에서(baseline, 4));
        assertThat(decision.message()).contains("삭제 대상 20건").contains("상한 5건");
    }

    // 깨뜨릴 변경: defaults() 의 상한을 0 으로 두기
    @Test
    @DisplayName("기본 정책의 상한은 5000줄이다")
    void 기본_정책의_상한은_5000줄이다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        // when
        var 같음 = guard.evaluate(5000, 200_000, "기준 스냅샷");
        var 넘음 = guard.evaluate(5001, 200_000, "기준 스냅샷");
        // then
        assertThat(DeletionGuardPolicy.defaults()).isEqualTo(new DeletionGuardPolicy(true, 0.3, 10, 5000));
        assertThat(같음.aborted()).isFalse();
        assertThat(넘음.aborted()).isTrue();
        assertThat(넘음.message()).contains("상한 5000건").doesNotContain("임계치");
    }
}

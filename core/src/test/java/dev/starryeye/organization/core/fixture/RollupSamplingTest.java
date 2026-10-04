package dev.starryeye.organization.core.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class RollupSamplingTest {

    private static final OrgChart CHART = OrgChartFixture.오천명();

    /** 아이디를 바꾸지 않은 5천 명 조직도에서 기본 표본이 고르던 직원들. 아이디 정렬 순 균등 간격이다. */
    private static final List<String> 예전_표본 = List.of(
            "corp.u0", "mgt3_0.u0", "new4_0.u0", "biz5_0.u0", "mfg6_0.u0", "mfg6_0.u5",
            "biz.u0", "biz5_18.u7", "biz5_4.u0", "dev5_12.u1", "dev5_31.u2", "dev6_17.u7",
            "dev6_46.u6", "dev6_75.u5", "mfg5_14.u4", "mfg5_26.u17", "mfg6_10.u16", "mfg6_23.u3",
            "mfg6_36.u6", "mfg6_49.u9", "mfg6_62.u10", "mfg6_75.u13", "mfg6_88.u16", "new4_1.u17",
            "plant.u1057", "plant.u1287", "plant.u1516", "plant.u306", "plant.u536", "plant.u766");

    @Test
    @DisplayName("아이디를 바꾸지 않은 조직도는 예전과 똑같은 표본을 고른다")
    void 바꾸지_않은_조직도의_표본은_그대로다() {
        // given, when
        var 표본 = RollupSampling.기본값().표본을_고른다(CHART);

        // then
        assertThat(표본).containsExactlyElementsOf(예전_표본);
    }

    @Test
    @DisplayName("서로 다른 무작위 아이디로 바꿔도 원래 조직도 아이디로 보면 같은 직원을 고른다 — 서버 발급 id 가 표본을 흔들지 않는다")
    void 무작위로_바꿔도_같은_직원을_고른다() {
        // given — 같은 조직도를 두 번, 매번 다른 UUID 로 바꾼다(서버가 id 를 발급하는 것처럼)
        var 첫째 = CHART.아이디를_바꾼다(무작위_번역기());
        var 둘째 = CHART.아이디를_바꾼다(무작위_번역기());

        // when
        var 첫째표본 = RollupSampling.기본값().표본을_고른다(첫째);
        var 둘째표본 = RollupSampling.기본값().표본을_고른다(둘째);

        // then
        assertThat(첫째표본).as("바뀐 아이디 자체는 번역마다 다르다").doesNotContainAnyElementsOf(둘째표본);
        assertThat(첫째표본.stream().map(첫째::원래아이디).toList()).containsExactlyElementsOf(예전_표본);
        assertThat(둘째표본.stream().map(둘째::원래아이디).toList()).containsExactlyElementsOf(예전_표본);
    }

    /** 아이디마다 처음 물을 때 UUID 를 발급하고 이후엔 같은 값을 돌려준다 — 서버가 id 를 기억하는 것과 같다. */
    private static UnaryOperator<String> 무작위_번역기() {
        var 발급 = new HashMap<String, String>();
        return id -> 발급.computeIfAbsent(id, 원래 -> UUID.randomUUID().toString());
    }
}

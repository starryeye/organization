package dev.starryeye.organization.core.fixture;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ④ 롤업 검증이 볼 직원을 고른다.
 *
 * <p>전 직원을 다 돌면 5,024명 × 조상 체인 + 자손 가지라 Check 가 수만 번이 된다. 매 시나리오
 * 단계마다 도는 검증이므로 그 비용을 감당할 수 없다. 그래서 표본을 쓴다.
 *
 * <p><b>깊이별 대표와 겸직은 언제나 들어간다.</b> 무작위 표본만 쓰면 가장 얕은 체인, 가장 깊은
 * 체인, 다중 경로 — 즉 이 조직도를 이렇게 만든 이유 전부가 어느 실행에서는 빠진다.
 *
 * <p>나머지는 아이디 정렬 순으로 균등하게 집는다. 난수를 쓰지 않는 이유는 실패가 재현돼야
 * 하기 때문이다 — 어제 통과하고 오늘 깨지는 검증은 통과했다는 사실 자체를 못 믿는다.
 *
 * @param 추가표본수 대표들 외에 더 볼 직원 수. 0 이면 대표만 본다
 */
public record RollupSampling(int 추가표본수) {

    public RollupSampling {
        if (추가표본수 < 0) {
            throw new IllegalArgumentException("표본 수는 음수일 수 없습니다: " + 추가표본수);
        }
    }

    /**
     * 기본 30명. 대표 6명 + 24명이면 조상 체인과 자손 가지를 합쳐 Check 가 수백 번 수준이라,
     * 시나리오 36건이 매 단계 돌아도 감당된다.
     */
    public static RollupSampling 기본값() {
        return new RollupSampling(24);
    }

    /** 표본을 아예 쓰지 않고 <b>전 직원</b>을 본다. 비싸다 — 최종 확인용이다. */
    public static RollupSampling 전수() {
        return new RollupSampling(Integer.MAX_VALUE);
    }

    public List<String> 표본을_고른다(OrgChart chart) {
        Landmarks l = chart.landmarks();
        Set<String> 표본 = new LinkedHashSet<>(List.of(
                l.L2직속직원(), l.L3직속직원(), l.L4직속직원(),
                l.L5직속직원(), l.L6직속직원(), l.겸직직원()));

        List<String> 전체 = chart.snapshot().users().keySet().stream().sorted().toList();
        if (추가표본수 >= 전체.size()) {
            표본.addAll(전체);
            return List.copyOf(표본);
        }
        if (추가표본수 > 0) {
            // 앞에서부터 자르면 한쪽 부문에 몰린다 — 아이디가 조직코드로 시작하기 때문이다
            int 간격 = Math.max(1, 전체.size() / 추가표본수);
            for (int i = 0; i < 전체.size() && 표본.size() < 추가표본수 + 6; i += 간격) {
                표본.add(전체.get(i));
            }
        }
        return List.copyOf(표본);
    }
}

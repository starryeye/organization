package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;

import java.util.HashSet;
import java.util.Set;

/**
 * LDAP 전체 동기화가 잘못된 결과(검색 베이스·필터 실수로 일부가 크게 빠진 응답)를 가져왔을 때
 * 권한이 한 번에 크게 날아가는 것을 막는다. 사람 소속이 하나도 없는 그림(필터 오류로 0건 등)은 여기까지 오지 않는다 — LDAP 전략이 데이터 오류로
 * 먼저 멈추고 {@code force} 로도 넘기지 못한다(설계 2026-10-27 §13).
 *
 * <p>LDAP 의 스냅샷 비교, 멈춘 뒤 훑어 맞추기, 재적재에 적용한다(설계 2026-09-30 §4.3) — {@code FORCED}·{@code force} 로만
 * 건너뛴다. SCIM 의 의도된 단건 삭제에는 적용하지 않는다.
 *
 * <p><b>걸려도 디렉터리가 명시한 회수는 먼저 본다(설계 2026-10-20 §5.2).</b> 정상적인 큰 개편 하나로 가드가 걸리면 다음 회차도 같은 델타로 다시 걸려,
 * 사람이 {@code force} 할 때까지 퇴사자 회수가 매일 멈춘다. 그래서 전체가 넘으면 그 가운데 디렉터리가 "막힘"으로 명시한 지우기만 떼어 같은 가드
 * (같은 기준 수, 같은 임계치, 같은 상한)로 다시 본다. 그것마저 넘으면(시계 오류·잘못된 정책으로 대량 비활성) 지금처럼 전부 보류한다 — 비활성을 가드 밖으로
 * 통째로 빼지 않는 까닭이다.
 *
 * <p><b>비율에 절대 수 상한을 OR 로 더한다(설계 2026-11-13 G1~G3).</b> 비율만으로는 큰 배포에서 막는 양이 수만 줄이라, 지울 줄 수가
 * {@code maxDeletes} 를 초과해도 걸린다. 상한은 {@code min-baseline} 과 상관없이 보고, {@code 0} 이하면 끈다. 가드를 끄면 상한도 끈다.
 */
public class DeletionGuard {

    private final DeletionGuardPolicy policy;

    public DeletionGuard(DeletionGuardPolicy policy) {
        this.policy = policy;
    }

    /**
     * 설계 2026-10-20 §5.2 의 순서로 판정한다.
     * <ol>
     *   <li>지울 줄 전체가 통과하면 진행한다.</li>
     *   <li>넘으면 지울 줄 가운데 비활성으로 생긴 것({@code deletes ∩ inactiveMemberTuples})만 떼어 같은 가드(같은 상한 포함)로 본다. 비어 있지 않고 통과하면 그것만
     *       반영하게 한다({@link GuardDecision#revokeOnly}).</li>
     *   <li>아니면 전부 보류한다.</li>
     * </ol>
     * 어느 쪽이든 가드가 걸렸으면 메시지는 전체 지우기의 초과 문구다.
     *
     * @param deletes              이번에 지울 줄 전체
     * @param inactiveMemberTuples 디렉터리가 막았다고 명시한 직원의 소속 줄({@code TupleMapper.inactiveMemberTuples})
     * @param baselineCount        비율의 기준 수
     * @param baselineLabel        메시지에 쓸 기준의 이름("기준 스냅샷", "훑은 장부")
     */
    public GuardDecision evaluate(Set<RelationTuple> deletes, Set<RelationTuple> inactiveMemberTuples, int baselineCount,
                                  String baselineLabel) {
        GuardDecision 전체 = evaluate(deletes.size(), baselineCount, baselineLabel);
        if (!전체.aborted()) {
            return 전체;
        }
        Set<RelationTuple> 비활성_회수 = 겹치는_줄(deletes, inactiveMemberTuples);
        if (비활성_회수.isEmpty() || evaluate(비활성_회수.size(), baselineCount, baselineLabel).aborted()) {
            return 전체;
        }
        return GuardDecision.revokeOnly(비활성_회수, 전체.message());
    }

    /** 작은 쪽을 돌며 겹치는 줄을 모은다 — 10만 명 규모에서 큰 집합을 복사하지 않는다. */
    private static Set<RelationTuple> 겹치는_줄(Set<RelationTuple> a, Set<RelationTuple> b) {
        Set<RelationTuple> 작은 = a.size() <= b.size() ? a : b;
        Set<RelationTuple> 큰 = 작은 == a ? b : a;
        Set<RelationTuple> 겹침 = new HashSet<>();
        for (RelationTuple tuple : 작은) {
            if (큰.contains(tuple)) {
                겹침.add(tuple);
            }
        }
        return 겹침;
    }

    /**
     * 지울 수와 기준 수로 판정한다 — 장부를 훑어 맞출 때는 기준이 스냅샷이 아니라 훑은 장부다(설계 2026-09-30 §4.3).
     *
     * <p>순서(설계 2026-11-13 §3.1): 가드가 꺼졌으면 진행한다. 아니면 상한 초과({@code maxDeletes > 0} 이고 지울 수 &gt; 상한,
     * 기준 수와 무관)와 임계치 초과(기준 수 &ge; {@code min-baseline} 이고 비율 &gt; 임계치)를 따로 보아, 하나라도 넘으면 중단한다.
     * 문구는 넘은 것에 따라 세 꼴이다: {@code 임계치 30.0%를}(비율만, 예전 문구와 같다), {@code 상한 5000건을}(상한만),
     * {@code 임계치 30.0%와 상한 5000건을}(둘 다). 기준 수 0 은 지울 수도 0 이라 상한에 닿지 않는다.
     *
     * @param baselineLabel 메시지에 쓸 기준의 이름("기준 스냅샷", "훑은 장부")
     */
    public GuardDecision evaluate(int deleteCount, int baselineCount, String baselineLabel) {
        if (!policy.enabled()) {
            return GuardDecision.proceed();
        }

        double ratio = (double) deleteCount / baselineCount;
        boolean 상한_넘음 = policy.maxDeletes() > 0 && deleteCount > policy.maxDeletes();
        boolean 임계치_넘음 = baselineCount >= policy.minBaseline() && ratio > policy.thresholdRatio();
        if (!상한_넘음 && !임계치_넘음) {
            return GuardDecision.proceed();
        }

        double 임계치 = policy.thresholdRatio() * 100;
        String 넘은_것;
        if (임계치_넘음 && 상한_넘음) {
            넘은_것 = "임계치 %.1f%%와 상한 %d건을".formatted(임계치, policy.maxDeletes());
        } else if (임계치_넘음) {
            넘은_것 = "임계치 %.1f%%를".formatted(임계치);
        } else {
            넘은_것 = "상한 %d건을".formatted(policy.maxDeletes());
        }
        return GuardDecision.abort(
                "삭제 대상 %d건(%s %d건의 %.1f%%)이 %s 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요"
                        .formatted(deleteCount, baselineLabel, baselineCount, ratio * 100, 넘은_것));
    }
}

package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.ReactivationHold;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 막힌 계정이 한꺼번에 활성으로 읽히는 사고(속성 파싱 오류·검색 범위 변경)가 그대로 권한 복구로 이어지는 것을 막는다.
 *
 * <p><b>기준과 재활성(설계 2026-11-24 R2).</b> 기준은 직전 스냅샷의 비활성 소속 줄(I)에서 {@code user:} 접두사를 뗀 서로 다른 직원 id 다.
 * 재활성은 그 가운데 이번 그림에 있고 활성인 직원이다. 그림에서 사라진 직원(퇴사)은 재활성이 아니지만 기준에는 든다.
 *
 * <p><b>판정(R3).</b> 삭제 가드와 같은 모양이다. 가드가 꺼졌으면 보지 않는다. 아니면 상한 초과({@code maxReactivations > 0} 이고 재활성 수 &gt; 상한,
 * 기준 수와 무관)와 임계치 초과(기준 수 &ge; {@code min-baseline} 이고 비율 &gt; 임계치)를 따로 보아 하나라도 넘으면 보류한다.
 *
 * <p><b>보류(R4) — 그림만 고친다.</b> 걸리면 이번 회차의 재활성 전부를 비활성으로 되돌린 그림′ 을 준다. 그림 하나를 고치는 것이라 뒤의 단계
 * (매핑·삭제 가드·멈춤 내역·현재상태 교체·스냅샷 I)는 모두 그림′ 을 본다. 이 클래스는 그 밖의 것을 쓰거나 읽지 않는다.
 */
public class ReactivationGuard {

    private final ReactivationGuardPolicy policy;

    public ReactivationGuard(ReactivationGuardPolicy policy) {
        this.policy = policy;
    }

    /**
     * 강제 회차의 가드(설계 2026-11-24 R9) — 비율을 끄고 상한을 M 으로 본다. 설정에서 가드를 꺼 두었어도 켠다. 비율은 기준 수가
     * {@code min-baseline} 이상일 때만 보므로 그것을 {@code int} 의 끝으로 두어 닿지 않게 한다(임계치 1.0 도 넘을 수 없다).
     */
    public static ReactivationGuard forced(int maxReactivations) {
        return new ReactivationGuard(new ReactivationGuardPolicy(true, 1.0, Integer.MAX_VALUE, maxReactivations));
    }

    /** 직전 I 와 이번 그림으로 판정한다. 보류면 그림′ 과 내역과 문구를 준다. */
    public ReactivationCheck check(Set<RelationTuple> previousInactive, DirectorySnapshot picture) {
        if (!policy.enabled()) {
            return ReactivationCheck.pass(picture);
        }
        String userPrefix = RelationTuple.USER_TYPE + ":";
        Set<String> baseline = new HashSet<>();
        for (RelationTuple tuple : previousInactive) {
            if (tuple.user().startsWith(userPrefix)) {
                baseline.add(tuple.user().substring(userPrefix.length()));
            }
        }
        Set<String> reactivated = new HashSet<>();
        for (String id : baseline) {
            DirectoryUser user = picture.users().get(id);
            if (user != null && user.active()) {
                reactivated.add(id);
            }
        }
        if (reactivated.isEmpty()) {
            return ReactivationCheck.pass(picture);
        }

        int count = reactivated.size();
        int baselineCount = baseline.size();
        double ratio = (double) count / baselineCount;
        boolean 상한_넘음 = policy.maxReactivations() > 0 && count > policy.maxReactivations();
        boolean 임계치_넘음 = baselineCount >= policy.minBaseline() && ratio > policy.thresholdRatio();
        if (!상한_넘음 && !임계치_넘음) {
            return ReactivationCheck.pass(picture);
        }

        double 임계치 = policy.thresholdRatio() * 100;
        String 넘은_것;
        if (임계치_넘음 && 상한_넘음) {
            넘은_것 = "임계치 %.1f%%와 상한 %d명을".formatted(임계치, policy.maxReactivations());
        } else if (임계치_넘음) {
            넘은_것 = "임계치 %.1f%%를".formatted(임계치);
        } else {
            넘은_것 = "상한 %d명을".formatted(policy.maxReactivations());
        }
        String message = ("재활성 대상 %d명(직전 비활성 소속 직원 %d명의 %.1f%%)이 %s 초과했습니다. "
                + "강제 실행하려면 실행 기록의 reactivationHold 를 확인한 뒤 force=true 와 maxReactivations(허용할 재활성 직원 수)로 재요청하세요")
                .formatted(count, baselineCount, ratio * 100, 넘은_것);

        Map<String, DirectoryUser> users = new HashMap<>(picture.users());
        for (String id : reactivated) {
            users.put(id, users.get(id).withActive(false));
        }
        DirectorySnapshot 그림_프라임 = new DirectorySnapshot(users, picture.groups(), picture.assignedEdges());
        return new ReactivationCheck(그림_프라임, ReactivationHold.of(reactivated), message);
    }
}

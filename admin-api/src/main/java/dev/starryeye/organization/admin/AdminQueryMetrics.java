package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.query.AccessPath;
import dev.starryeye.organization.core.query.EmployeeDetail;
import dev.starryeye.organization.core.query.OrgMember;
import dev.starryeye.organization.core.query.OrganizationDetail;
import dev.starryeye.organization.core.query.Page;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 조회하면서 부수적으로 드리프트를 센다.
 *
 * <p>follow-ups §6 에서 별도 감지 장치를 두지 않기로 했으므로, 이 카운터가 조회한 범위에
 * 한해서나마 유일한 신호다. 0 이 아니면 수동 재적재를 실행할 근거가 된다.
 *
 * <p>Check 를 못 한 항목은 세지 않는다 — 모른다는 것과 어긋났다는 것은 다르다.
 *
 * <p><b>순환으로 설명되는 어긋남은 드리프트가 아니다.</b> 저장된 계층에 순환이 있으면
 * {@code TupleMapper} 가 간선 하나를 <b>일부러</b> 버리므로 파생값과 실제가 갈린다(설계 §8.3).
 * 이것을 {@code authz_drift_detected} 로 세면 카운터가 영영 0 으로 돌아오지 않는다 —
 * 재적재는 같은 결정적 순환 제거를 다시 돌려 같은 간선을 또 버리기 때문이다. 설계 §10 은
 * 이 카운터가 0 이 아닌 것을 수동 재적재의 근거로 삼는데, 고칠 수 없는 값이 섞이면 그 신호는
 * 소음이 된다. 그래서 순환 줄은 {@code authz_cycle_divergence} 라는 다른 카운터로 옮겨
 * 보이기는 하되 재적재 신호는 울리지 않게 한다.
 */
public class AdminQueryMetrics {

    private final Counter drift;
    private final Counter cycleDivergence;
    private final Counter checkFailed;
    private final Counter checks;

    public AdminQueryMetrics(MeterRegistry registry) {
        this.drift = Counter.builder("authz_drift_detected")
                .description("현재상태가 요구하는 권한과 OpenFGA 판정이 갈린 건수 (순환으로 설명되는 것은 제외)")
                .register(registry);
        this.cycleDivergence = Counter.builder("authz_cycle_divergence")
                .description("저장된 계층의 순환 때문에 파생값과 판정이 갈린 건수. 재적재로 고쳐지지 않는다")
                .register(registry);
        this.checkFailed = Counter.builder("authz_check_failed")
                .description("Check 호출이 실패해 판정을 보류한 건수")
                .register(registry);
        // 실패율(authz_check_failed / authz_checks_total)을 계산하려면 분모가 있어야 한다.
        // 결과 한 줄이 곧 Check 한 번이므로 성공·실패를 가리지 않고 여기서 센다.
        this.checks = Counter.builder("authz_checks_total")
                .description("Check 호출 수. 실패율의 분모다")
                .register(registry);
    }

    public void recordEmployeeDetail(EmployeeDetail detail) {
        detail.paths().forEach(this::record);
    }

    public void recordOrganizationDetail(OrganizationDetail detail) {
        recordMembers(detail.members());
    }

    public void recordMembers(Page<OrgMember> page) {
        page.items().forEach(member -> {
            checks.increment();
            if (member.openFgaCheck() == null) {
                checkFailed.increment();
            } else if (member.active() != member.openFgaCheck()) {
                drift.increment();
            }
        });
    }

    private void record(AccessPath path) {
        checks.increment();
        if (path.openFgaCheck() == null) {
            checkFailed.increment();
            return;
        }
        if (!path.drifted()) {
            return;
        }
        if (path.cycle()) {
            cycleDivergence.increment();
        } else {
            drift.increment();
        }
    }
}

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
 */
public class AdminQueryMetrics {

    private final Counter drift;
    private final Counter checkFailed;

    public AdminQueryMetrics(MeterRegistry registry) {
        this.drift = Counter.builder("authz_drift_detected")
                .description("현재상태가 요구하는 권한과 OpenFGA 판정이 갈린 건수")
                .register(registry);
        this.checkFailed = Counter.builder("authz_check_failed")
                .description("Check 호출이 실패해 판정을 보류한 건수")
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
            if (member.openFgaCheck() == null) {
                checkFailed.increment();
            } else if (member.active() != member.openFgaCheck()) {
                drift.increment();
            }
        });
    }

    private void record(AccessPath path) {
        if (path.openFgaCheck() == null) {
            checkFailed.increment();
        } else if (path.drifted()) {
            drift.increment();
        }
    }
}

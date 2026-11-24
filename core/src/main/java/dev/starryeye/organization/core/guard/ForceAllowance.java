package dev.starryeye.organization.core.guard;

/**
 * 강제 회차의 허용 수 — 운영자가 멈춘 회차의 내역({@code guardHold}·{@code reactivationHold})을 보고 허용한 수다. 가드마다 하나씩, 준 것만 바뀐다
 * (설계 2026-11-24 R9).
 *
 * <p>강제 회차도 가드를 건너뛰지 않는다. 삭제 가드는 비율 없이 {@code maxDeletes} 를 상한으로({@link DeletionGuard#forced}, 설계 2026-11-20
 * F1·F2), 재활성 가드는 비율 없이 {@code maxReactivations} 를 상한으로({@link ReactivationGuard#forced}) 같은 가드를 본다. 확인한 뒤 디렉터리가
 * 더 망가져 수가 허용을 넘으면 다시 멈춘다. 주지 않은(null) 가드는 설정대로다.
 *
 * <p>둘 다 없거나 준 값이 1 미만이면 만들 수 없다. 설정의 {@code 0} 은 "끔"이라 요청의 0 을 받으면 같은 수가 두 뜻이 된다(설계 2026-11-20 F3).
 * 컨트롤러의 400 과 별개로 core 가 스스로 지킨다.
 *
 * @param maxDeletes       허용할 지울 줄 수 — 직원 수가 아니라 줄 수다. 내역의 {@code lines.total} 과 같은 단위다. 없으면 null
 * @param maxReactivations 허용할 재활성 직원 수 — 줄이 아니라 직원 수다. 없으면 null
 */
public record ForceAllowance(Integer maxDeletes, Integer maxReactivations) {

    public ForceAllowance {
        if (maxDeletes == null && maxReactivations == null) {
            throw new IllegalArgumentException("허용 수가 하나는 있어야 한다: maxDeletes·maxReactivations 둘 다 없음");
        }
        if (maxDeletes != null && maxDeletes < 1) {
            throw new IllegalArgumentException("허용할 지울 줄 수는 1 이상이어야 한다: " + maxDeletes);
        }
        if (maxReactivations != null && maxReactivations < 1) {
            throw new IllegalArgumentException("허용할 재활성 직원 수는 1 이상이어야 한다: " + maxReactivations);
        }
    }

    /** 지울 줄 수만 허용한다 — 재활성 가드는 설정대로다. */
    public ForceAllowance(int maxDeletes) {
        this(maxDeletes, null);
    }

    /** 강제 회차의 결론 앞에 붙이는 문구(설계 2026-11-20 F4, 2026-11-24 R11). 준 수만 적고 숫자는 쉼표 없이 적는다. */
    public String note() {
        if (maxReactivations == null) {
            return "강제 실행 — 지울 줄 허용 " + maxDeletes + "건";
        }
        if (maxDeletes == null) {
            return "강제 실행 — 재활성 허용 " + maxReactivations + "명";
        }
        return "강제 실행 — 지울 줄 허용 " + maxDeletes + "건·재활성 허용 " + maxReactivations + "명";
    }
}

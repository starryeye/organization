package dev.starryeye.organization.core.guard;

/**
 * 강제 회차의 허용 수 — 운영자가 멈춘 회차의 내역({@code guardHold})을 보고 허용한 지울 <b>줄</b> 수 N(설계 2026-11-20 F1).
 *
 * <p>강제 회차도 삭제 가드를 건너뛰지 않는다. 같은 가드를 비율 없이 이 N 을 상한으로 본다({@link DeletionGuard#forced}, 설계 2026-11-20 F2).
 * 확인한 뒤 디렉터리가 더 망가져 지울 줄이 N 을 넘으면 다시 멈춘다.
 *
 * <p>1 이상만 만들 수 있다. 설정의 {@code max-deletes: 0} 은 "끔"이라 요청의 0 을 받으면 같은 수가 두 뜻이 된다(설계 2026-11-20 F3). 컨트롤러의
 * 400 과 별개로 core 가 스스로 지킨다.
 *
 * @param maxDeletes 허용할 지울 줄 수 — 직원 수가 아니라 줄 수다. 내역의 {@code lines.total} 과 같은 단위다
 */
public record ForceAllowance(int maxDeletes) {

    public ForceAllowance {
        if (maxDeletes < 1) {
            throw new IllegalArgumentException("허용할 지울 줄 수는 1 이상이어야 한다: " + maxDeletes);
        }
    }

    /** 강제 회차의 결론 앞에 붙이는 문구(설계 2026-11-20 F4). 숫자는 쉼표 없이 적는다. */
    public String note() {
        return "강제 실행 — 지울 줄 허용 " + maxDeletes + "건";
    }
}

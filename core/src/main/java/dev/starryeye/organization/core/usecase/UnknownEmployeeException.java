package dev.starryeye.organization.core.usecase;

/** 현재상태에 그 직원이 없다 — 즉시 회수는 락 밖에서 이것으로 돌려보낸다(설계 2026-11-28 V2②). 직원 id 는 싣지 않는다. */
public class UnknownEmployeeException extends RuntimeException {

    public UnknownEmployeeException() {
        super("현재상태에 그 직원이 없습니다 — 직원 id 를 확인하세요");
    }
}

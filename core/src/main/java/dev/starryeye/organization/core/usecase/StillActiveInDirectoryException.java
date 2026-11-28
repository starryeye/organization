package dev.starryeye.organization.core.usecase;

/** 디렉터리에서 활성인 직원은 거두지 않는다(설계 2026-11-28 V2④). 직원 id 는 싣지 않는다. */
public class StillActiveInDirectoryException extends RuntimeException {

    public StillActiveInDirectoryException() {
        super("디렉터리에서 활성인 직원은 거두지 않습니다 — 디렉터리에서 먼저 막으세요");
    }
}

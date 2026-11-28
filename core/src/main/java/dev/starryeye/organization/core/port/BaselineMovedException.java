package dev.starryeye.organization.core.port;

/**
 * 즉시 회수 표시를 적거나 정리하려 했으나 기준선이 그 사이 움직였다 — 포인터 번호가 다르거나 "기록 중" 칸이 생겼다(설계 2026-11-28 V6·V8).
 * 아무것도 쓰지 않았다.
 */
public class BaselineMovedException extends RuntimeException {

    public BaselineMovedException(String message) {
        super(message);
    }
}

package dev.starryeye.organization.core.port;

/** 즉시 회수 표시가 한도(256KB)를 넘는다 — 아무것도 쓰지 않았다(설계 2026-11-28 V9). */
public class RevocationMarksFullException extends RuntimeException {

    public RevocationMarksFullException() {
        super("즉시 회수 표시가 한도(256KB)를 넘습니다 — 다음 동기화가 기준선을 새로 남긴 뒤 다시 거세요. "
                + "여럿이면 디렉터리에서 막은 뒤 POST /admin/sync/full 을 쓰세요");
    }
}

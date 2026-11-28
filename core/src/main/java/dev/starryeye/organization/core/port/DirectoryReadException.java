package dev.starryeye.organization.core.port;

/** 디렉터리 읽기가 일시 장애 신호가 아닌 이유로 실패했다 — 모르는 종류까지 이쪽이다(설계 2026-11-28 V2⑤). 500 으로 옮긴다. */
public class DirectoryReadException extends RuntimeException {

    public DirectoryReadException(String message, Throwable cause) {
        super(message, cause);
    }
}

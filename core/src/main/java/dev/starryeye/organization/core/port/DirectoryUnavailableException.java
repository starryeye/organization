package dev.starryeye.organization.core.port;

/** 디렉터리 읽기가 일시 장애 신호로 실패했다 — 잠시 뒤 다시 보내면 나을 수 있다(설계 2026-11-28 V2⑤). 503 + {@code Retry-After} 로 옮긴다. */
public class DirectoryUnavailableException extends TemporaryFailureException {

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, 기본_대기, cause);
    }
}

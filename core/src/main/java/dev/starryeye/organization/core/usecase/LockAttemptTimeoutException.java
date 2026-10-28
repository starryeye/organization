package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.TemporaryFailureException;

/**
 * 락 획득 시도가 시도 한도로 끊겼다 — <b>결과를 모른다</b>(설계 2026-10-27 §6). 서버에서는 성공했을 수 있다. 조건 실패(남이 쥠)인
 * {@link LockUnavailableException} 과 가르려고 그 하위로 두지 않는다 — 조건 실패는 쉬었다 다시 시도하지만, 이것은 같은 토큰으로 곧바로 다시 시도하고
 * 포기할 때 그 토큰으로 반납한다({@link AcquireBackoff}). 대기는 {@link #기본_대기} 다.
 */
public class LockAttemptTimeoutException extends TemporaryFailureException {

    public LockAttemptTimeoutException(String message) {
        super(message, 기본_대기);
    }

    /** 저장소가 던진 시간 초과를 물고 간다 — 로그에 어느 한도로 끊겼는지 남긴다. */
    public LockAttemptTimeoutException(String message, Throwable cause) {
        super(message, 기본_대기, cause);
    }
}

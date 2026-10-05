package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.ApiException;
import dev.openfga.sdk.errors.FgaApiValidationError;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;

import java.time.Duration;
import java.util.Optional;

/**
 * OpenFGA SDK 예외 하나가 일시 장애인지 알아본다(설계 2026-10-05 §3.3). 거절(400 검증 오류 — 다시 보내도 같다)이 아닌 {@code ApiException} 은 일시 장애다.
 * 쓰기의 재시도 분류({@link OpenFgaErrors})와 같은 기준이다.
 */
public final class OpenFgaTemporaryFailures implements TemporaryFailureRecognizer {

    @Override
    public Optional<Duration> 재시도_대기(Throwable error) {
        if (error instanceof FgaApiValidationError) {
            return Optional.empty();
        }
        return error instanceof ApiException
                ? Optional.of(TemporaryFailureException.기본_대기)
                : Optional.empty();
    }
}

package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.FgaError;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;

import java.time.Duration;
import java.util.Optional;

/**
 * OpenFGA SDK 예외 하나가 일시 장애인지 알아본다(설계 2026-10-05 §3.3). SDK 의 {@code FgaError.isRetryable()}(429·5xx, 501 제외)을 따른다.
 * 네트워크 실패는 SDK 가 {@code ApiException} 으로 감싸므로 connector-scim 분류기의 I/O 규칙이 잡는다.
 * 응답 해석 실패·인증·store 없음은 일시 장애가 아니다(설계 2026-10-05 §3.3).
 */
public final class OpenFgaTemporaryFailures implements TemporaryFailureRecognizer {

    @Override
    public Optional<Duration> 재시도_대기(Throwable error) {
        return error instanceof FgaError fga && fga.isRetryable()
                ? Optional.of(TemporaryFailureException.기본_대기)
                : Optional.empty();
    }
}

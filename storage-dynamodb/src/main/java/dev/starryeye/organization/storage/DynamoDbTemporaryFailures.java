package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;

import java.time.Duration;
import java.util.Optional;

/**
 * DynamoDB(AWS SDK v2) 예외 하나가 일시 장애인지 알아본다(설계 2026-10-05 §3.3). SDK 자신의 재시도 분류를 따른다 — 네트워크·시간 초과, 스로틀링, 5xx, SDK 가 재시도 가능하다고 표시한 것.
 * 조건 실패·검증 오류 같은 4xx 는 다시 보내도 같아 일시 장애가 아니다.
 */
public final class DynamoDbTemporaryFailures implements TemporaryFailureRecognizer {

    @Override
    public Optional<Duration> 재시도_대기(Throwable error) {
        if (error instanceof SdkClientException) {
            return Optional.of(TemporaryFailureException.기본_대기);
        }
        if (error instanceof SdkServiceException service
                && (service.isThrottlingException() || service.statusCode() >= 500 || service.retryable())) {
            return Optional.of(TemporaryFailureException.기본_대기);
        }
        return Optional.empty();
    }
}

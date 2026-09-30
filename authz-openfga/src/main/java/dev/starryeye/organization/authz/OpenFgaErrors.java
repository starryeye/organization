package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.FgaApiValidationError;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * OpenFGA 오류를 "다시 보내도 같은 거절"과 "다시 보낼 만한 일시 오류"로 나눈다(점검 M16, 설계 2026-09-30 §6.1). 쓰기와 장부 훑기가 같은
 * 분류를 쓴다.
 */
final class OpenFgaErrors {

    private OpenFgaErrors() {
    }

    /**
     * OpenFGA 가 요청 내용을 거절했다(400 — 없는 타입, 너무 긴 아이디 등). SDK 가 CompletionException 등으로 감싸 주므로 원인 사슬을 따라가며 본다.
     */
    static boolean 거절인가(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof FgaApiValidationError) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 일시 오류만 {@code maxRetries} 번 200ms 부터 늘려 가며 다시 시도한다. 다 쓰면 원래 오류를 그대로 던진다 — "Retries exhausted" 로 원인을
     * 가리지 않는다(②-1 최종 리뷰).
     */
    static Retry 일시_오류만_다시(int maxRetries) {
        return Retry.backoff(maxRetries, Duration.ofMillis(200))
                .filter(error -> !거절인가(error))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}

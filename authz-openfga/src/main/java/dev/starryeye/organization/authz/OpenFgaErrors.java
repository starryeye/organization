package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.FgaApiNotFoundError;
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
        return 원인_사슬에_있는가(error, FgaApiValidationError.class);
    }

    /**
     * 물은 store 가 없다(404 — 지워졌거나 그 번호를 만든 적 없다). 일시 오류도, 요청 거절도 아니다. 원인 사슬을 따라가며 본다.
     */
    static boolean 없는_store_인가(Throwable error) {
        return 원인_사슬에_있는가(error, FgaApiNotFoundError.class);
    }

    private static boolean 원인_사슬에_있는가(Throwable error, Class<? extends Throwable> 종류) {
        Throwable cause = error;
        while (cause != null) {
            if (종류.isInstance(cause)) {
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

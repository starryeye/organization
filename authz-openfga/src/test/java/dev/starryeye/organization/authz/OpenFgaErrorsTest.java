package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.FgaApiValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenFGA 오류를 "다시 보내도 같은 거절"과 "다시 보낼 만한 일시 오류"로 나눈다 (점검 M16, 설계 2026-09-30 §6.1).
 */
class OpenFgaErrorsTest {

    private static FgaApiValidationError 거절() {
        return new FgaApiValidationError("type 'nosuchtype' not found", 400,
                HttpHeaders.of(Map.of(), (name, value) -> true), "{\"code\":\"validation_error\"}");
    }

    @Test
    @DisplayName("거절(400)은 감싸져 와도 알아본다 — 그 밖은 거절이 아니다")
    void 거절을_알아본다() {
        // when, then
        assertThat(OpenFgaErrors.거절인가(거절())).isTrue();
        assertThat(OpenFgaErrors.거절인가(new CompletionException(new IllegalStateException("감쌈", 거절())))).isTrue();
        assertThat(OpenFgaErrors.거절인가(new IllegalStateException("연결 거부"))).isFalse();
    }

    @Test
    @DisplayName("거절은 다시 시도하지 않는다 — 다시 보내도 같다")
    void 거절은_다시_시도하지_않는다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        Mono<Void> 보내기 = Mono.defer(() -> {
            시도.incrementAndGet();
            return Mono.error(거절());
        });

        // when, then — FgaApiValidationError 는 checked Exception 이라 Mono#block() 이
        // reactor.core.Exceptions$ReactiveException 으로 감싼다. 원인을 본다
        assertThatThrownBy(() -> 보내기.retryWhen(OpenFgaErrors.일시_오류만_다시(2)).block())
                .hasCauseInstanceOf(FgaApiValidationError.class);
        assertThat(시도).hasValue(1);
    }

    @Test
    @DisplayName("일시 오류는 다시 시도하고, 다 쓰면 'Retries exhausted' 가 아니라 원래 오류를 던진다")
    void 일시_오류는_다시_시도하고_원인을_남긴다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        Mono<Void> 보내기 = Mono.defer(() -> {
            시도.incrementAndGet();
            return Mono.error(new IllegalStateException("연결 거부"));
        });

        // when, then
        assertThatThrownBy(() -> 보내기.retryWhen(OpenFgaErrors.일시_오류만_다시(2)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("연결 거부");
        assertThat(시도).hasValue(3);
    }
}

package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.ApiException;
import dev.openfga.sdk.errors.FgaApiInternalError;
import dev.openfga.sdk.errors.FgaApiValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenFGA SDK 예외 하나가 일시 장애인지 알아보는 규칙 (설계 2026-10-05 §3.3).
 */
class OpenFgaTemporaryFailuresTest {

    private static final HttpHeaders 빈_헤더 = HttpHeaders.of(Map.of(), (a, b) -> true);

    private final OpenFgaTemporaryFailures 인식기 = new OpenFgaTemporaryFailures();

    @Test
    @DisplayName("OpenFGA 의 거절(400 검증 오류)이 아닌 ApiException 은 일시 장애다 — 내부 오류(500)")
    void 거절이_아닌_오류는_일시_장애다() {
        // given — SDK 0.9.11 의 공개 생성자(String, int, java.net.http.HttpHeaders, String)
        ApiException 내부오류 = new FgaApiInternalError("internal", 500, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(내부오류)).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("OpenFGA 의 거절(FgaApiValidationError)은 일시 장애가 아니다 — 다시 보내도 같다(우리 버그다)")
    void 거절은_아니다() {
        // given
        ApiException 거절 = new FgaApiValidationError("validation", 400, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(거절)).isEmpty();
        assertThat(인식기.재시도_대기(new IllegalStateException("x"))).isEmpty();
    }
}

package dev.starryeye.organization.authz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.errors.ApiException;
import dev.openfga.sdk.errors.FgaApiAuthenticationError;
import dev.openfga.sdk.errors.FgaApiInternalError;
import dev.openfga.sdk.errors.FgaApiNotFoundError;
import dev.openfga.sdk.errors.FgaApiRateLimitExceededError;
import dev.openfga.sdk.errors.FgaApiValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenFGA SDK 예외 하나가 일시 장애인지 알아보는 규칙 (설계 2026-10-05 §3.3).
 * SDK 자신의 재시도 분류({@code FgaError.isRetryable()} — 429 와 501 을 뺀 5xx)만 따른다.
 */
class OpenFgaTemporaryFailuresTest {

    private static final HttpHeaders 빈_헤더 = HttpHeaders.of(Map.of(), (a, b) -> true);

    private final OpenFgaTemporaryFailures 인식기 = new OpenFgaTemporaryFailures();

    @Test
    @DisplayName("SDK 가 다시 시도할 만하다고 보는 내부 오류(500)는 일시 장애다")
    void 내부_오류_500은_일시_장애다() {
        // given — SDK 0.9.11 의 공개 생성자(String, int, java.net.http.HttpHeaders, String)
        ApiException 내부오류 = new FgaApiInternalError("internal", 500, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(내부오류)).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("SDK 가 다시 시도할 만하다고 보는 요청 한도 초과(429)는 일시 장애다")
    void 요청_한도_초과_429는_일시_장애다() {
        // given
        ApiException 한도초과 = new FgaApiRateLimitExceededError("rate limit", 429, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(한도초과)).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("501(구현 없음)은 SDK 도 다시 시도하지 않는다 — 일시 장애가 아니다")
    void 구현_없음_501은_아니다() {
        // given
        ApiException 구현없음 = new FgaApiInternalError("not implemented", 501, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(구현없음)).isEmpty();
    }

    @Test
    @DisplayName("인증 실패(401)는 일시 장애가 아니다 — 자격 증명 설정 오류라 다시 보내도 같다")
    void 인증_실패_401은_아니다() {
        // given
        ApiException 인증실패 = new FgaApiAuthenticationError("unauthenticated", 401, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(인증실패)).isEmpty();
    }

    @Test
    @DisplayName("store 없음(404)은 일시 장애가 아니다 — 다시 보내도 같다")
    void store_없음_404는_아니다() {
        // given
        ApiException 없음 = new FgaApiNotFoundError("store not found", 404, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(없음)).isEmpty();
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

    @Test
    @DisplayName("응답 해석 실패를 감싼 ApiException(상태 0)은 일시 장애가 아니다 — SDK 는 2xx 본문을 읽다 실패하면 이렇게 감싼다")
    void 응답_해석_실패는_아니다() {
        // given — SDK 의 HttpRequestAttempt.deserializeResponse 가 하는 대로 Jackson 의 실제 실패를 ApiException 으로 감싼다
        ApiException 해석실패 = new ApiException(깨진_JSON_해석_실패());

        // when, then
        assertThat(인식기.재시도_대기(해석실패)).isEmpty();
    }

    @Test
    @DisplayName("네트워크 실패를 감싼 ApiException 은 인식기가 아니라 사슬의 I/O 규칙이 잡는다 — 인식기 자체는 빈 값이다")
    void 네트워크_실패는_사슬의_IO_규칙이_잡는다() {
        // given — SDK 는 연결 실패를 ApiException(IOException) 으로 감싼다
        ApiException 연결실패 = new ApiException(new ConnectException("refused"));

        // when, then
        assertThat(인식기.재시도_대기(연결실패)).isEmpty();
    }

    private static JsonProcessingException 깨진_JSON_해석_실패() {
        try {
            new ObjectMapper().readTree("{");
        } catch (JsonProcessingException e) {
            return e;
        }
        throw new AssertionError("깨진 JSON 은 해석에 실패해야 한다");
    }
}

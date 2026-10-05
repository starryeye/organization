package dev.starryeye.organization.scim;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.server.ServerWebInputException;

import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TemporaryFailureClassifierTest {

    @Test
    @DisplayName("원인 사슬 어디에든 일시 장애 표지가 있으면 그 시간이다 — CompletionException·감싼 예외 안 깊이 있어도")
    void 사슬_깊이의_표지를_찾는다() {
        // given
        var 분류기 = TemporaryFailureClassifier.표지만();
        var 깊이 = new CompletionException(new IllegalStateException("감쌈",
                new LockUnavailableException("재적재 중", Duration.ofSeconds(60))));

        // when, then
        assertThat(분류기.재시도_대기(깊이)).contains(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("어댑터 인식기가 알아본 라이브러리 예외는 일시 장애다")
    void 인식기를_묻는다() {
        // given
        class 라이브러리오류 extends RuntimeException {
        }
        var 분류기 = new TemporaryFailureClassifier(List.of(e -> e instanceof 라이브러리오류
                ? Optional.of(Duration.ofSeconds(10)) : Optional.empty()));

        // when, then
        assertThat(분류기.재시도_대기(new RuntimeException(new 라이브러리오류()))).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("원인 사슬 어디의 I/O 실패·시간 초과든 일시 장애다")
    void 입출력_실패는_일시_장애다() {
        // given
        var 분류기 = TemporaryFailureClassifier.표지만();

        // when, then
        assertThat(분류기.재시도_대기(new CompletionException(new java.net.ConnectException("refused")))).contains(Duration.ofSeconds(10));
        assertThat(분류기.재시도_대기(new java.util.concurrent.TimeoutException())).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("모르는 예외는 일시 장애가 아니다 — 500(버그)으로 간다")
    void 모르는_예외는_아니다() {
        // when, then
        assertThat(TemporaryFailureClassifier.표지만().재시도_대기(new NullPointerException())).isEmpty();
    }

    @Test
    @DisplayName("원인이 자기 자신을 가리키는 순환 사슬도 끝낸다")
    void 순환_사슬도_끝난다() {
        // given
        var 분류기 = TemporaryFailureClassifier.표지만();
        var 앞 = new RuntimeException("앞");
        var 뒤 = new RuntimeException("뒤", 앞);
        앞.initCause(뒤);

        // when, then
        assertThat(분류기.재시도_대기(앞)).isEmpty();
    }

    @Test
    @DisplayName("깨진 JSON(JsonParseException)은 일시 장애가 아니다 — IOException 의 하위 타입이지만 입출력이 아니고 다시 보내도 같다")
    void 깨진_JSON은_일시_장애가_아니다() {
        // given — 실제 파서가 던진 예외를 WebFlux 가 감싸 오는 모양 그대로
        Throwable 파싱_실패 = catchThrowable(() -> new ObjectMapper().readValue("{\"userName\":", Map.class));
        var 감싼 = new ServerWebInputException("Failed to read HTTP message", null, new DecodingException("JSON decoding error", 파싱_실패));

        // when, then
        assertThat(파싱_실패).isInstanceOf(JsonParseException.class);
        assertThat(TemporaryFailureClassifier.표지만().재시도_대기(감싼)).isEmpty();
    }

    @Test
    @DisplayName("타입이 안 맞는 JSON(MismatchedInputException)도 일시 장애가 아니다")
    void 타입이_안_맞는_JSON도_일시_장애가_아니다() {
        // given
        Throwable 매핑_실패 = catchThrowable(() -> new ObjectMapper().readValue("{\"userName\":{\"x\":1}}", 이름만.class));
        var 감싼 = new DecodingException("JSON decoding error", 매핑_실패);

        // when, then
        assertThat(매핑_실패).isInstanceOf(MismatchedInputException.class);
        assertThat(TemporaryFailureClassifier.표지만().재시도_대기(감싼)).isEmpty();
    }

    @Test
    @DisplayName("Jackson 예외 뒤에 진짜 입출력 실패가 있으면 그것은 일시 장애다 — 사슬은 끝까지 본다")
    void Jackson_예외_뒤의_입출력_실패는_찾는다() {
        // given
        var 깊이 = new DecodingException("JSON decoding error",
                new JsonParseException(null, "읽다 끊김", new SocketException("reset")));

        // when, then
        assertThat(TemporaryFailureClassifier.표지만().재시도_대기(깊이)).contains(Duration.ofSeconds(10));
    }

    record 이름만(String userName) {
    }
}

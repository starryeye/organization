package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("I/O 실패와 시간 초과는 일시 장애다 — OpenFGA HTTP 클라이언트가 감싸지 않고 올리는 것")
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
}

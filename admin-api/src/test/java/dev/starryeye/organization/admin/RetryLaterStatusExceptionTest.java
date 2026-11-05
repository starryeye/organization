package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RetryLaterStatusException#락_경합이면_바꾼다} — 쓰기 요청의 컨트롤러가 락 경합을 409 가 아닌 503 으로 내려고 {@code onErrorMap} 에 거는 변환이다.
 */
class RetryLaterStatusExceptionTest {

    @Test
    @DisplayName("원인 사슬에 락 경합이 있으면 503 예외로 바꾼다 — 사유와 Retry-After 는 안쪽 락 경합의 값이고 원래 예외를 원인으로 든다")
    void 사슬의_락_경합을_503_으로_바꾼다() {
        // given — 2.3초는 3초로 올린다
        LockUnavailableException 락 = new LockUnavailableException("쥐고 있다", Duration.ofMillis(2300));
        RuntimeException 감싼 = new RuntimeException("바깥", new IllegalStateException("가운데", 락));

        // when
        Throwable 바뀐 = RetryLaterStatusException.락_경합이면_바꾼다(감싼);

        // then
        assertThat(바뀐).isInstanceOf(RetryLaterStatusException.class);
        ResponseStatusException 상태 = (ResponseStatusException) 바뀐;
        assertThat(상태.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(상태.getReason()).isEqualTo("쥐고 있다");
        assertThat(상태.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        assertThat(상태.getCause()).isSameAs(감싼);
    }

    @Test
    @DisplayName("직접 던진 락 경합도 같다")
    void 직접_던진_락_경합도_503_으로_바꾼다() {
        // given
        LockUnavailableException 락 = LockUnavailableException.잡혀_있다(null);

        // when
        Throwable 바뀐 = RetryLaterStatusException.락_경합이면_바꾼다(락);

        // then
        assertThat(바뀐).isInstanceOf(RetryLaterStatusException.class);
        assertThat(((ResponseStatusException) 바뀐).getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }

    @Test
    @DisplayName("사슬에 락 경합이 없으면 그 예외를 그대로 돌려준다 — 원인이 서로를 가리키는 순환 사슬도 끝난다")
    void 락_경합이_없으면_그대로다() {
        // given
        IllegalStateException 평범한 = new IllegalStateException("버그");
        RuntimeException 앞 = new RuntimeException("앞");
        RuntimeException 뒤 = new RuntimeException("뒤", 앞);
        앞.initCause(뒤);

        // when, then
        assertThat(RetryLaterStatusException.락_경합이면_바꾼다(평범한)).isSameAs(평범한);
        assertThat(RetryLaterStatusException.락_경합이면_바꾼다(앞)).isSameAs(앞);
    }
}

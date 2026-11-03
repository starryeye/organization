package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import dev.starryeye.organization.core.port.TemporaryFailureClassifier;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.usecase.LockAttemptTimeoutException;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리 API 예외 처리기(설계 2026-10-27 §10). 경로마다 정한 예외를 던지는 컨트롤러에 처리기를 달아 상태·헤더·본문을 본다.
 */
class AdminExceptionHandlerTest {

    private static final String 락_경합_사유 = "다른 동기화·재적재가 진행 중입니다";
    private static final String 일시_장애_문구 = "일시 장애 — 잠시 뒤 다시 시도한다";

    private WebTestClient client;

    @BeforeEach
    void 처리기를_결선한다() {
        client = WebTestClient.bindToController(new 던지는_컨트롤러())
                .controllerAdvice(new AdminExceptionHandler(TemporaryFailureClassifier.표지만()))
                .build();
    }

    @Test
    @DisplayName("락 경합을 감싼 409 는 409 이고 컨트롤러가 준 사유를 싣는다 — Retry-After 는 없다")
    void 감싼_락_경합은_409_이고_사유를_싣는다() {
        // when, then
        client.get().uri("/wrapped-conflict").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("Retry-After")
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Conflict")
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").isEqualTo(락_경합_사유);
    }

    @Test
    @DisplayName("원인 사슬의 락 경합은 TemporaryFailureException 의 하위지만 503 이 아니라 409 다")
    void 원인_사슬의_락_경합은_503_이_아니라_409_다() {
        // when, then — 분류기를 먼저 보면 이것이 503 이 된다
        client.get().uri("/lock-conflict").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("Retry-After")
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("REBUILD"));
    }

    @Test
    @DisplayName("다른 예외가 락 경합을 감싼 409 를 한 번 더 감싸도 409 이고 같은 사유다")
    void 두_겹_감싼_409_도_409_다() {
        // when, then
        client.get().uri("/double-wrapped-conflict").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("Retry-After")
                .expectBody()
                .jsonPath("$.detail").isEqualTo(락_경합_사유);
    }

    @Test
    @DisplayName("일시 장애는 503 이고 Retry-After 가 초다 — 예외 문구 대신 정한 문구를 싣는다")
    void 일시_장애는_503_과_Retry_After_다() {
        // when, then
        client.get().uri("/temporary").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Retry-After", "10")
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Service Unavailable")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo(일시_장애_문구);
    }

    @Test
    @DisplayName("1초보다 짧은 대기도 Retry-After 는 1 로 올린다 — 정수 초이고 0 이 아니다")
    void 짧은_대기는_1초로_올린다() {
        // when, then
        client.get().uri("/short-temporary").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "1");
    }

    @Test
    @DisplayName("락 획득이 시도 한도로 끊긴 것은 조건 실패가 아니라 일시 장애라 503 이다")
    void 락_획득_시간_초과는_503_이다() {
        // when, then
        client.get().uri("/lock-attempt-timeout").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "10")
                .expectBody()
                .jsonPath("$.detail").isEqualTo(일시_장애_문구);
    }

    @Test
    @DisplayName("요청 파라미터 형식 오류는 400 이고 스프링의 이유를 싣는다")
    void 입력_오류는_400_이고_스프링의_이유를_싣는다() {
        // when, then
        client.get().uri("/number?n=abc").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).isNotBlank());
    }

    @Test
    @DisplayName("이유 없이 던진 ResponseStatusException 은 그 상태이고 상태 문구를 싣는다")
    void 이유_없는_상태_예외는_상태_문구를_싣는다() {
        // when, then
        client.get().uri("/bare-404").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Not Found");
    }

    @Test
    @DisplayName("상태 예외가 머리를 들고 있으면 응답에 그대로 옮긴다 — 범위 부족 403 의 WWW-Authenticate 가 그 예")
    void 상태_예외의_헤더를_옮긴다() {
        // when, then
        client.get().uri("/with-header").exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("force=true은 admin.destructive 범위가 필요하다");
    }

    @Test
    @DisplayName("머리를 든 상태 예외를 다른 예외가 감싸도 매치된 상태 예외의 머리를 옮긴다")
    void 감싼_상태_예외의_헤더도_옮긴다() {
        // when, then
        client.get().uri("/wrapped-with-header").exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
    }

    @Test
    @DisplayName("머리가 없는 상태 예외의 응답에는 덧붙는 머리가 없다")
    void 머리가_없는_상태_예외는_머리를_더하지_않는다() {
        // when, then
        client.get().uri("/bare-404").exchange()
                .expectStatus().isNotFound()
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    @DisplayName("그 밖의 예외는 500 이고 예외 문구를 싣지 않는다 — 인증 없는 엔드포인트라 내부 사정이 나가면 안 된다")
    void 그_밖은_500_이고_예외_문구를_싣지_않는다() {
        // when
        var 결과 = client.get().uri("/bug").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("Retry-After")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Internal Server Error")
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.detail").isEqualTo("내부 오류")
                .returnResult();

        // then
        assertThat(new String(결과.getResponseBody(), StandardCharsets.UTF_8)).doesNotContain("비밀");
    }

    /** 경로마다 정한 예외를 던진다. 처리기가 달리는 표지({@link AdminApi})를 단다. */
    @AdminApi
    @RestController
    static class 던지는_컨트롤러 {

        @GetMapping("/wrapped-conflict")
        Mono<String> 감싼_락_경합() {
            return Mono.error(락_경합을_감싼_409());
        }

        @GetMapping("/lock-conflict")
        Mono<String> 락_경합() {
            return Mono.error(LockUnavailableException.잡혀_있다(LockPurpose.REBUILD));
        }

        @GetMapping("/double-wrapped-conflict")
        Mono<String> 두_겹_감싼_락_경합() {
            return Mono.error(new IllegalStateException("감쌈", 락_경합을_감싼_409()));
        }

        @GetMapping("/temporary")
        Mono<String> 일시_장애() {
            return Mono.error(new TemporaryFailureException("x", Duration.ofSeconds(10)));
        }

        @GetMapping("/short-temporary")
        Mono<String> 짧은_일시_장애() {
            return Mono.error(new TemporaryFailureException("x", Duration.ofMillis(300)));
        }

        @GetMapping("/lock-attempt-timeout")
        Mono<String> 락_획득_시간_초과() {
            return Mono.error(new LockAttemptTimeoutException("락 획득 시도가 한도로 끊겼다"));
        }

        @GetMapping("/number")
        Mono<String> 숫자(@RequestParam int n) {
            return Mono.just("n=" + n);
        }

        @GetMapping("/bare-404")
        Mono<String> 이유_없는_404() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        @GetMapping("/with-header")
        Mono<String> 머리를_든_상태_예외() {
            return Mono.error(범위_부족_403());
        }

        @GetMapping("/wrapped-with-header")
        Mono<String> 머리를_든_상태_예외를_감쌌다() {
            return Mono.error(new IllegalStateException("감쌈", 범위_부족_403()));
        }

        @GetMapping("/bug")
        Mono<String> 버그() {
            return Mono.error(new NullPointerException("비밀"));
        }

        /**
         * 컨트롤러가 범위 부족으로 던지는 모양 그대로 — 사유와 {@code WWW-Authenticate} 를 든 403. 실제 예외는 web-security 에 있고 이 모듈은 그것에 기대지
         * 않으므로, 머리를 {@code getHeaders()} 로 내놓는 상태 예외를 같은 모양으로 만든다.
         */
        private static ResponseStatusException 범위_부족_403() {
            return new ResponseStatusException(HttpStatus.FORBIDDEN, "force=true은 admin.destructive 범위가 필요하다") {
                @Override
                public HttpHeaders getHeaders() {
                    HttpHeaders 머리 = new HttpHeaders();
                    머리.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
                    return 머리;
                }
            };
        }

        /** 두 앱의 AdminSyncController 가 락 경합을 감싸는 모양 그대로 */
        private static ResponseStatusException 락_경합을_감싼_409() {
            return new ResponseStatusException(HttpStatus.CONFLICT, 락_경합_사유,
                    LockUnavailableException.잡혀_있다(LockPurpose.SYNC));
        }
    }
}

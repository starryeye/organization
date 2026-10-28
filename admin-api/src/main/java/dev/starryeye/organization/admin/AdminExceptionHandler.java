package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.port.TemporaryFailureClassifier;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * 관리 API({@link AdminApi} 를 단 컨트롤러)의 예외를 RFC 9457 problem details({@code application/problem+json})로 낸다(설계 2026-10-27 §10).
 * 칸은 {@code type}(about:blank)·{@code title}(상태 문구)·{@code status}·{@code detail} 이다.
 *
 * <p>판정은 아래 순서다. 원인 사슬은 같은 예외를 두 번 보지 않고 따라간다.
 * <ol>
 *   <li>사슬에 {@link ResponseStatusException} 이 있으면 그 상태다. detail 은 그 사유이고, 없으면 상태 문구다.
 *       {@code ServerWebInputException}(요청 파라미터 형식 오류 등)도 여기다 — 400 에 스프링의 이유를 싣는다.</li>
 *   <li>사슬에 {@link LockUnavailableException} 이 있으면 409 다. detail 은 그 메시지다.</li>
 *   <li>분류기가 일시 장애로 보면 503 + {@code Retry-After}(초) 다. detail 은 정한 문구다. 예외 문구는 로그로만 간다.</li>
 *   <li>그 밖은 500 이다. detail 은 {@code 내부 오류} 뿐이다 — 이 엔드포인트는 인증이 없어 예외 문구(저장소·라이브러리 메시지)를 싣지 않는다.</li>
 * </ol>
 *
 * <p><b>락 경합(2)을 분류기(3)보다 먼저 본다.</b> {@link LockUnavailableException} 은 {@code TemporaryFailureException} 의 하위라 분류기가
 * 일시 장애로 읽는다. 게다가 두 앱의 {@code AdminSyncController} 는 락 경합을 원인으로 품은 {@code ResponseStatusException(409)} 로 감싸는데,
 * 분류기는 원인 사슬을 따라가므로 분류기를 먼저 보면 409 가 503 이 된다. 그래서 {@code ResponseStatusException} 은 자기 상태로 먼저 답하고(1),
 * 감싸지 않은 락 경합도 분류기 앞에서 409 로 거른다(2). 락 획득이 시도 한도로 끊긴 것({@code LockAttemptTimeoutException})은
 * {@code LockUnavailableException} 의 하위가 아니다 — 남이 쥔 것이 아니라 결과를 모르는 시간 초과라 분류기를 거쳐 503 이 된다.
 */
@Slf4j
@RestControllerAdvice(annotations = AdminApi.class)
@RequiredArgsConstructor
public class AdminExceptionHandler {

    /** 503 의 detail — 예외 문구(락 쥔 쪽의 용도·라이브러리 메시지)를 싣지 않는다 */
    static final String 일시_장애_문구 = "일시 장애 — 잠시 뒤 다시 시도한다";
    /** 500 의 detail */
    static final String 내부_오류_문구 = "내부 오류";

    private final TemporaryFailureClassifier classifier;

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> handle(Throwable error) {
        Optional<ResponseStatusException> 상태 = 원인에서_찾는다(error, ResponseStatusException.class);
        if (상태.isPresent()) {
            ResponseStatusException 예외 = 상태.get();
            String 사유 = 예외.getReason() != null ? 예외.getReason() : 상태_문구(예외.getStatusCode());
            return 응답(예외.getStatusCode(), 사유, null);
        }
        Optional<LockUnavailableException> 락_경합 = 원인에서_찾는다(error, LockUnavailableException.class);
        if (락_경합.isPresent()) {
            return 응답(HttpStatus.CONFLICT, 락_경합.get().getMessage(), null);
        }
        Optional<Duration> 대기 = classifier.재시도_대기(error);
        if (대기.isPresent()) {
            long 초 = 재시도_초(대기.get());
            log.warn("관리 API 요청이 일시 장애로 실패했다 — 503, Retry-After {}초", 초, error);
            return 응답(HttpStatus.SERVICE_UNAVAILABLE, 일시_장애_문구, 초);
        }
        log.error("관리 API 요청 처리 중 예기치 않은 오류", error);
        return 응답(HttpStatus.INTERNAL_SERVER_ERROR, 내부_오류_문구, null);
    }

    private static ResponseEntity<ProblemDetail> 응답(HttpStatusCode 상태, String detail, Long 재시도_초) {
        ResponseEntity.BodyBuilder 응답 = ResponseEntity.status(상태);
        if (재시도_초 != null) {
            응답 = 응답.header(HttpHeaders.RETRY_AFTER, String.valueOf(재시도_초));
        }
        return 응답.body(ProblemDetail.forStatusAndDetail(상태, detail));
    }

    /** 원인 사슬에서 이 종류의 첫 예외 — 같은 예외를 두 번 보지 않는다(원인이 자기 자신을 가리키는 순환도 끝낸다) */
    private static <T extends Throwable> Optional<T> 원인에서_찾는다(Throwable error, Class<T> 종류) {
        Set<Throwable> 본것 = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable 원인 = error; 원인 != null && 본것.add(원인); 원인 = 원인.getCause()) {
            if (종류.isInstance(원인)) {
                return Optional.of(종류.cast(원인));
            }
        }
        return Optional.empty();
    }

    private static String 상태_문구(HttpStatusCode 상태) {
        HttpStatus 알려진 = HttpStatus.resolve(상태.value());
        return 알려진 != null ? 알려진.getReasonPhrase() : String.valueOf(상태.value());
    }

    /** Retry-After 에 쓰는 초 — 정수 초(RFC 9110 §10.2.3)이고, 1초보다 짧은 대기도 0 이 아니라 1 로 올린다. SCIM 라우터와 같은 규칙이다. */
    private static long 재시도_초(Duration 대기) {
        long 올림 = 대기.toSeconds() + (대기.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, 올림);
    }
}

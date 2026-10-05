package dev.starryeye.organization.scim;

import com.fasterxml.jackson.core.JacksonException;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 예외가 일시 장애(다시 보내면 나을 수 있다)인지 원인 사슬을 따라가며 가른다(설계 2026-10-05 §3.3). core 표지 → 어댑터 인식기 → I/O 실패·시간 초과 순으로 본다.
 * I/O 규칙은 Jackson 의 해석·매핑 실패({@code JacksonException}, {@code IOException} 하위)를 뺀다 — 다시 보내도 같은 결과다.
 * 어디에도 없으면 일시 장애가 아니다 — 500(버그).
 */
public final class TemporaryFailureClassifier {

    private final List<TemporaryFailureRecognizer> recognizers;

    public TemporaryFailureClassifier(List<TemporaryFailureRecognizer> recognizers) {
        this.recognizers = List.copyOf(recognizers);
    }

    /** core 표지와 I/O 실패만 본다 — 어댑터가 없는 테스트·조립의 기본값 */
    public static TemporaryFailureClassifier 표지만() {
        return new TemporaryFailureClassifier(List.of());
    }

    public Optional<Duration> 재시도_대기(Throwable error) {
        Set<Throwable> 본것 = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable 원인 = error; 원인 != null && 본것.add(원인); 원인 = 원인.getCause()) {
            if (원인 instanceof TemporaryFailureException 일시) {
                return Optional.of(일시.retryAfter());
            }
            for (TemporaryFailureRecognizer 인식기 : recognizers) {
                Optional<Duration> 대기 = 인식기.재시도_대기(원인);
                if (대기.isPresent()) {
                    return 대기;
                }
            }
            // Jackson 의 해석·매핑 실패는 IOException 의 하위 타입이지만 입출력이 아니다 — 다시 보내도 같은 결과다(설계 2026-10-05 §3.3)
            if ((원인 instanceof IOException && !(원인 instanceof JacksonException)) || 원인 instanceof TimeoutException) {
                return Optional.of(TemporaryFailureException.기본_대기);
            }
        }
        return Optional.empty();
    }
}

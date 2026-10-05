package dev.starryeye.organization.core.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 라이브러리 예외 하나가 일시 장애인지 알아본다 — 그 라이브러리를 가진 어댑터가 구현해 빈으로 낸다(설계 2026-10-05 §3.3). 원인 사슬은 호출자가 따라간다.
 */
@FunctionalInterface
public interface TemporaryFailureRecognizer {

    /** 일시 장애면 기다릴 시간, 아니면 빈 값 */
    Optional<Duration> 재시도_대기(Throwable error);
}

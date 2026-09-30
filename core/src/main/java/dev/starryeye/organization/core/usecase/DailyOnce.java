package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.DailyJobClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 하루 한 번 작업을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1, 점검 M15). 인스턴스가 셋이면 매일 03:00 아카이빙(직원 10만 GetItem +
 * BatchCheck 약 2,200번 + 10만 줄 스냅샷)이 세 번 돌고 스냅샷도 셋 쌓였다. 날짜는 앱 시계의 UTC 날짜다.
 */
@Slf4j
@RequiredArgsConstructor
public class DailyOnce {

    private final DailyJobClaims claims;
    private final Clock clock;

    /** 오늘 표지를 잡은 인스턴스만 {@code work} 를 돌린다. 못 잡으면 빈 Mono — 다른 인스턴스가 이미 했다. */
    public <T> Mono<T> run(String job, Mono<T> work) {
        return Mono.defer(() -> {
            LocalDate 오늘 = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            return claims.claim(job, 오늘).flatMap(잡았다 -> {
                if (!잡았다) {
                    log.info("오늘({}) {} 은 다른 인스턴스가 이미 했다 — 건너뛴다", 오늘, job);
                    return Mono.<T>empty();
                }
                return work;
            });
        });
    }
}

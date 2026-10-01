package dev.starryeye.organization.core.port;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 하루 한 번 도는 작업을 클러스터 전체에서 한 번만 돌리기 위한 표지(설계 2026-09-30 §5.1). 먼저 잡은 인스턴스만 그날 작업을 돌린다.
 */
public interface DailyJobClaims {

    /** 그날 표지를 잡는다. 잡았으면 true, 이미 누가 잡았으면 false. */
    Mono<Boolean> claim(String job, LocalDate day);
}

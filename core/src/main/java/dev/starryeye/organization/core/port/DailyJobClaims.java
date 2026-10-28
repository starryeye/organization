package dev.starryeye.organization.core.port;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 하루 한 번 도는 작업을 클러스터 전체에서 한 번만 돌리기 위한 표지(설계 2026-09-30 §5.1, 2026-10-27 §9). 표지는 잡음(CLAIMED)과 완료(DONE) 둘이다.
 * 잡은 인스턴스만 그날 작업을 돌린다. 성공하면 완료로 적고, 성공이 아니면 풀어 다음 시도(창이 끝났으면 다음 날)가 다시 잡는다. 잡은 채 죽은 인스턴스의 표지는 임대가 끝나면
 * 다시 잡힌다.
 *
 * <p>완료·해제는 표지를 쥔 owner 만 한다 — 임대를 잃은 인스턴스의 늦은 해제가 남이 새로 잡은 표지를 지우면 그날 일이 한 번 더 돈다.
 */
public interface DailyJobClaims {

    /** 없거나, CLAIMED 인데 임대가 끝났으면 owner 로 잡는다. DONE 이면 못 잡는다 */
    Mono<Boolean> claim(String job, LocalDate day, String owner, Instant leaseUntil);

    /** owner 가 쥔 표지만 DONE 으로 — 남의 것이면 아무것도 하지 않는다 */
    Mono<Void> complete(String job, LocalDate day, String owner);

    /** owner 가 쥔 표지만 지운다 — 남의 것이면 아무것도 하지 않는다 */
    Mono<Void> release(String job, LocalDate day, String owner);
}

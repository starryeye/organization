package dev.starryeye.organization.core.port;

import reactor.core.publisher.Mono;

/**
 * 디렉터리(LDAP)에서 직원 한 사람의 계정 상태를 읽는다 — 즉시 회수가 락을 잡기 전에 묻는다(설계 2026-11-28 V2③, §5.2).
 *
 * <p>일시 장애 신호(연결 실패·서버가 바쁨·상한 초과)면 {@link DirectoryUnavailableException}, 그 밖의 읽기 실패는 {@link DirectoryReadException} 이다.
 * 어느 쪽도 그대로 올라가 요청이 실패한다 — 정기 읽기처럼 다시 읽지 않는다.
 */
public interface DirectoryAccountSource {

    /**
     * @param employeeId 직원 id
     * @param externalId 현재상태가 가진 그 직원의 DN. 없으면 null
     */
    Mono<AccountState> accountState(String employeeId, String externalId);

    enum AccountState {
        /** 활성 엔트리가 있다 */
        ACTIVE,
        /** 엔트리는 있으나 모두 막혔다 */
        BLOCKED,
        /** 디렉터리에 엔트리가 없다 */
        ABSENT
    }
}

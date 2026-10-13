package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.audit.AuditEvent;
import dev.starryeye.organization.core.audit.AuditRecord;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.query.Page;
import reactor.core.publisher.Mono;

/**
 * 변경 이력 저장소(설계 2026-10-10 §4.1). 사건 하나와 그 사건의 대상별 줄을 받아 기록하고, 대상 하나의 이력을 최신순으로 한 쪽씩 읽는다.
 */
public interface AuditLog {

    /**
     * 사건의 대상별 줄을 쓴다. 정해진 횟수만큼 다시 보낸 뒤에도 쓰지 못한 줄은 세어 돌려준다 — 0 이면 다 썼다.
     */
    Mono<Integer> write(AuditEvent event);

    /**
     * 대상 하나의 이력을 최신순으로 {@code limit} 줄까지 읽는다. 기록이 없는 대상은 빈 쪽이다. 이 대상의 이력 커서가 아니거나 망가진 커서는
     * {@link IllegalArgumentException} 이다 — 호출자가 400 으로 옮긴다.
     *
     * @param limit 1 이상이어야 한다 — 구현은 쪽의 마지막 줄로 다음 커서를 만든다. 범위(1~100) 검사는 호출자(관리 API)의 몫이다
     */
    Mono<Page<AuditRecord>> history(MemberRef subject, String cursor, int limit);

    /** 아무것도 남기지 않고, 읽으면 늘 비었다 — 이력이 필요 없는 테스트와 옛 생성자가 쓴다. */
    AuditLog NONE = new AuditLog() {
        @Override
        public Mono<Integer> write(AuditEvent event) {
            return Mono.just(0);
        }

        @Override
        public Mono<Page<AuditRecord>> history(MemberRef subject, String cursor, int limit) {
            return Mono.just(Page.empty());
        }
    };
}

package dev.starryeye.organization.core.audit;

import lombok.extern.slf4j.Slf4j;
import reactor.util.context.ContextView;

/**
 * 감사의 행위자(설계 2026-10-28 인증 §5) — 변경을 일으킨 쪽이 누구인가를 한 문자열로 적는다. 값은 셋 중 하나의 꼴이다.
 *
 * <ul>
 *   <li>{@code client:<sub>} — 토큰으로 온 요청. {@code sub} 는 토큰을 받은 클라이언트의 id 다({@link #client}).</li>
 *   <li>{@code system:scheduler}·{@code system:archive} — 사람이 걸지 않은 예약 작업({@link #SCHEDULER}) 과 SCIM 아카이빙({@link #ARCHIVE}).</li>
 *   <li>{@code system:unknown} — Context 에서 찾지 못했다({@link #UNKNOWN}).</li>
 * </ul>
 *
 * <p>행위자는 Reactor Context 로 나른다. 인증 뒤 웹 필터나 예약 작업이 {@link #KEY} 로 싣고, core 는 {@link #from} 으로 읽는다 — core 는
 * Spring 을 모르고 Reactor 만 쓴다.
 */
@Slf4j
public final class Actor {

    /** Context 키. 웹 필터와 예약 작업이 이 키로 행위자 문자열을 싣는다. */
    public static final String KEY = Actor.class.getName();

    /** 예약된 LDAP 동기화. */
    public static final String SCHEDULER = "system:scheduler";

    /** SCIM 아카이빙 — 그 회차와 남은 회수 지우기. */
    public static final String ARCHIVE = "system:archive";

    /** Context 에서 찾지 못했다. 행위자가 없는 경로는 없어야 하므로 이 값이 보이면 싣는 자리가 빠진 것이다. */
    public static final String UNKNOWN = "system:unknown";

    private static final String CLIENT_PREFIX = "client:";

    private Actor() {
    }

    /** 토큰의 {@code sub} 로 만든 행위자. */
    public static String client(String sub) {
        return CLIENT_PREFIX + sub;
    }

    /**
     * Context 에서 행위자를 읽는다. 키가 없거나 쓸 수 있는 문자열이 아니면 {@link #UNKNOWN} 을 돌려주고 WARN 을 남긴다 — 오류를 내지 않는다.
     * 감사가 일을 막지 않는다는 원칙(감사 이력 설계 2026-10-10)을 지킨다.
     */
    public static String from(ContextView context) {
        Object value = context.getOrDefault(KEY, null);
        if (value instanceof String actor && !actor.isBlank()) {
            return actor;
        }
        log.warn("Context 에 행위자가 없어 {} 로 남긴다 — 행위자를 싣는 자리가 빠진 경로다", UNKNOWN);
        return UNKNOWN;
    }
}

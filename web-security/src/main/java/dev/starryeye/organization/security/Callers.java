package dev.starryeye.organization.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * 컨트롤러가 하는 범위 판정(설계 2026-10-28 §4.3, A4). 경로 규칙({@link ResourceServerSecurityConfig})이 지나보낸 요청 가운데 쿼리 값에 따라 범위가 더 필요한 것
 * — {@code force=true}·{@code mode=wipe} — 을 컨트롤러가 스프링이 바꾼 값({@code boolean force}, {@code mode})으로 본 뒤 여기서 판정한다. 필터에서 쿼리
 * 문자열을 직접 읽으면 스프링과 다르게 해석해({@code force=yes}·{@code 1} 도 true) 우회된다.
 */
public final class Callers {

    private Callers() {
    }

    /**
     * 호출자에게 그 범위의 권한({@code SCOPE_<범위>})이 없으면 {@link InsufficientScopeException}(403)을 던진다. 호출자가 없어도({@code null}) 던진다 —
     * 판정을 건너뛰지 않는다.
     *
     * @param 무엇 그 범위가 필요한 요청을 사람이 읽을 말로 — 예: {@code force=true}, {@code mode=wipe}. 403 사유의 앞머리가 된다
     */
    public static void 요구한다(Authentication caller, String scope, String 무엇) {
        String 권한 = Scopes.권한(scope);
        boolean 있다 = caller != null && caller.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(권한::equals);
        if (!있다) {
            throw new InsufficientScopeException(scope, 무엇);
        }
    }
}

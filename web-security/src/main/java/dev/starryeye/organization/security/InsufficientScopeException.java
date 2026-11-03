package dev.starryeye.organization.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 컨트롤러가 낸 범위 부족(설계 2026-10-28 §4.3·§4.4) — 403, 사유 {@code <무엇>은 <범위> 범위가 필요하다}. 관리 API 처리기가 problem details 로 바꾸고,
 * {@link #getHeaders()} 의 {@code WWW-Authenticate: Bearer error="insufficient_scope"}(RFC 6750 §3.1)를 응답에 옮긴다 — 보안 필터의 403 과 같은 머리다.
 */
public class InsufficientScopeException extends ResponseStatusException {

    public InsufficientScopeException(String scope, String 무엇) {
        super(HttpStatus.FORBIDDEN, 무엇 + "은 " + scope + " 범위가 필요하다");
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders 머리 = new HttpHeaders();
        머리.set(HttpHeaders.WWW_AUTHENTICATE, AuthFailureResponses.범위_부족);
        return HttpHeaders.readOnlyHttpHeaders(머리);
    }
}

package dev.starryeye.organization.security;

import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * 키 목록(jwk-set-uri)을 받지 못했다는 표지 — 토큰 탓이 아니라 검증하는 쪽의 장애다. 키 목록을 받는 자리({@link KeySetClient})에서만 만든다:
 * 연결 실패·시간 한도·2xx 가 아닌 상태·키 목록으로 해석되지 않는 본문.
 *
 * <p>응답은 원인으로 가른다. 이 표지가 원인 사슬에 있으면 503({@link AuthFailureResponses#토큰을_받지_않는다}), 없으면 디코더가 무엇으로 끝났든 잘못된 토큰 401
 * 이다({@link ResourceServerSecurityConfig}). 스프링이 낸 예외의 종류(예: {@code BadJwtException} 이 아닌 {@code JwtException})로 가르면 아무나 보낼 수 있는
 * 토큰(JWE 꼴 등)이 503 과 "장애" 경보를 꾸며 낸다.
 *
 * <p>{@link JwtException} 의 하위인 이유: 스프링 인증 관리자는 {@code BadJwtException} 이 아닌 {@link JwtException} 을 {@code AuthenticationServiceException}
 * 으로 바꿔 실패 처리기로 보낸다. 다른 예외면 인증 필터가 다루지 않아 500 이 된다.
 */
class KeySetUnavailableException extends JwtException {

    KeySetUnavailableException(String message) {
        super(message);
    }

    KeySetUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 원인 사슬의 첫 표지 — 같은 예외를 두 번 보지 않는다(원인이 자기를 가리키는 순환도 끝낸다) */
    static Optional<KeySetUnavailableException> 원인에서_찾는다(Throwable 오류) {
        Set<Throwable> 본것 = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable 원인 = 오류; 원인 != null && 본것.add(원인); 원인 = 원인.getCause()) {
            if (원인 instanceof KeySetUnavailableException 표지) {
                return Optional.of(표지);
            }
        }
        return Optional.empty();
    }
}

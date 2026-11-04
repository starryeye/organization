package dev.starryeye.authserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ClientCredentialsAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * client credentials 요청에 범위({@code scope})가 없으면 그 클라이언트에 등록된 범위를 모두 준다(설계 2026-10-28 §3.2, RFC 6749 §3.3 의 기본 범위).
 * IdP 화면에 범위 칸이 없거나 비워 둔 경우를 위해서다.
 *
 * <p><b>Spring Authorization Server(1.5)의 기본은 그렇지 않다</b> — 범위 없는 요청에 범위가 빈 토큰을 준다(JWT 에 {@code scope} 클레임이 없다. §16 에서
 * 확인). 범위는 요청을 토큰 요청 객체로 바꿀 때 정해지고 그 뒤로는 바꿀 길이 없으므로, 이 변환기가 기본 변환기의 결과에 등록 범위를 채운다. 토큰 엔드포인트의
 * 클라이언트 인증 필터가 먼저 돌아 보안 Context 에 인증된 클라이언트(등록 정보 포함)가 들어 있다. 범위를 보낸 요청은 그대로 두어 등록되지 않은 범위가
 * {@code invalid_scope} 로 거절되는 기본 동작을 지킨다.
 */
final class DefaultScopes implements AuthenticationConverter {

    private final OAuth2ClientCredentialsAuthenticationConverter 기본 = new OAuth2ClientCredentialsAuthenticationConverter();

    @Override
    public Authentication convert(HttpServletRequest request) {
        Authentication 변환 = 기본.convert(request);
        if (!(변환 instanceof OAuth2ClientCredentialsAuthenticationToken 요청) || !요청.getScopes().isEmpty()) {
            return 변환;
        }
        if (!(요청.getPrincipal() instanceof OAuth2ClientAuthenticationToken 클라이언트) || 클라이언트.getRegisteredClient() == null) {
            return 변환;
        }
        return new OAuth2ClientCredentialsAuthenticationToken(
                클라이언트, 클라이언트.getRegisteredClient().getScopes(), 요청.getAdditionalParameters());
    }
}

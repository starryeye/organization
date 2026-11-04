package dev.starryeye.authserver;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * 발급 기록을 남기지 않는 인가 저장소(설계 2026-10-28 §3.2). Spring Authorization Server 는 토큰을 발급할 때마다 인가 기록을 저장소에 넣고, 빈이 없으면
 * 메모리 저장소를 쓴다 — client credentials 토큰은 지울 일이 없어 쌓이기만 한다. 이 배포는 기록을 읽는 기능(토큰 조회 introspection·폐기 revocation)을
 * 쓰지 않고 그 끝점을 닫으므로({@link AuthServerSecurityConfig}) 저장하지 않고, 찾으면 늘 없다고 답한다. 그래서 app-auth 는 상태가 없다.
 *
 * <p>client credentials 발급은 저장만 하고 읽지 않는다. 인가 코드·리프레시 토큰처럼 기록을 다시 읽는 흐름은 이 저장소로는 돌지 않는다 — 이 배포는 그 흐름을
 * 쓰는 클라이언트를 등록하지 않는다.
 */
final class NoRetentionAuthorizationService implements OAuth2AuthorizationService {

    @Override
    public void save(OAuth2Authorization authorization) {
        // 남기지 않는다
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        // 남긴 것이 없다
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return null;
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return null;
    }
}

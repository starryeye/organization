package dev.starryeye.authserver;

import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerProperties;

import java.time.Duration;

/**
 * 기동 때 클라이언트·발급자 설정을 검사한다(설계 2026-10-28 §3.2·§3.5, A12). 부트가 {@code spring.security.oauth2.authorizationserver.*} 로 받은
 * 클라이언트를 본다. {@link AuthServerSecurityConfig} 가 인가 서버 필터 체인을 만들기 전에 부른다 — 그래야 풀리지 않은 {@code ${AUTH_ISSUER}} 가
 * Spring Authorization Server 의 "issuer must be a valid URL" 보다 먼저 이 문구로 멈춘다. 비밀은 오류 문구에 싣지 않는다.
 *
 * <ul>
 *   <li><b>수명.</b> 액세스 토큰 수명이 365일을 넘는 클라이언트가 있으면 멈춘다(늘 — 로컬도). RFC 7644 §7 은 bearer 토큰의 수명이 유한하길 요구하고,
 *       붙여 넣는 토큰(Okta 의 HTTP Header 방식)을 쓰는 IdP 용 클라이언트만 수명을 길게(예: 180일) 준다.
 *   <li><b>비밀.</b> {@code auth.require-hashed-secrets}(운영)인데 {@code {bcrypt}} 해시가 아닌 비밀이 있으면 멈춘다.
 *   <li><b>https 발급자의 비밀.</b> 발급자가 {@code https://} 면 그 설정과 상관없이 {@code {bcrypt}} 가 아닌 비밀을 멈춘다. 개발 클라이언트
 *       ({@code auth-server.yml} 의 {@code !prod} 문서 — {@code {noop}} 공개 비밀, {@code ops-break-glass} 는 {@code admin.destructive} 까지)는
 *       프로필 이름이 {@code prod} 가 아니면(예: {@code production}) 그대로 남는다. 거기에 발급자만 부트의 속성(환경 변수)으로 https 를 주면 그
 *       클라이언트들이 진짜 토큰을 낸다. 로컬 발급자는 {@code http://localhost:9000} 이라 개발 클라이언트는 http 발급자에서만 뜬다. 스킴은 대소문자를
 *       가리지 않는다(RFC 3986 §3.1) — {@code HTTPS://} 로 이 검사를 비껴가지 못한다.
 *   <li><b>발급자.</b> {@code auth.require-https}(운영)인데 발급자가 {@code https://} 가 아니면(없거나 풀리지 않은 자리표시자도) 멈춘다.
 * </ul>
 */
final class ClientSettingsCheck {

    /** 수명 상한. 넘으면 기동을 멈춘다 — 365일 꼭 맞는 것은 받는다. */
    static final Duration 수명_상한 = Duration.ofDays(365);

    private static final String HTTPS = "https://";

    private ClientSettingsCheck() {
    }

    static void 검사한다(OAuth2AuthorizationServerProperties 인가_서버, AuthServerProperties 설정) {
        String 발급자 = 인가_서버.getIssuer();
        boolean https_발급자 = 발급자 != null && 발급자.regionMatches(true, 0, HTTPS, 0, HTTPS.length());
        for (var 클라이언트 : 인가_서버.getClient().values()) {
            String 아이디 = 클라이언트.getRegistration().getClientId();
            if (클라이언트.getToken().getAccessTokenTimeToLive().compareTo(수명_상한) > 0) {
                throw new IllegalStateException("클라이언트 " + 아이디 + " 의 토큰 수명이 365일을 넘는다 — RFC 7644 §7");
            }
            String 비밀 = 클라이언트.getRegistration().getClientSecret();
            boolean 해시다 = 비밀 != null && 비밀.startsWith("{bcrypt}");
            if (설정.isRequireHashedSecrets() && !해시다) {
                throw new IllegalStateException("클라이언트 " + 아이디 + " 의 비밀이 {bcrypt} 해시가 아니다");
            }
            if (https_발급자 && !해시다) {
                throw new IllegalStateException("발급자가 https:// 인데 클라이언트 " + 아이디 + " 의 비밀이 {bcrypt} 해시가 아니다"
                        + " — {noop} 비밀(개발 클라이언트)은 http 발급자(로컬)에서만 받는다");
            }
        }
        if (설정.isRequireHttps() && (발급자 == null || !발급자.startsWith(HTTPS))) {
            throw new IllegalStateException("auth.require-https 인데 발급자가 https:// 가 아니다");
        }
    }
}

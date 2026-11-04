package dev.starryeye.organization.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리소스 서버의 {@code auth.*} 설정과 기동 검사(설계 2026-10-28 §4.1, A12). 키 목록·발급자 주소는 부트의
 * {@code spring.security.oauth2.resourceserver.jwt.*} 가 받고, 여기에는 운영이 켜는 요구만 둔다. {@link ResourceServerSecurityConfig} 가 보안 체인을
 * 만들기 전에 {@link #검사한다} 를 부른다 — 잘못된 설정이 조용히 "인증 없는 운영"이나 "평문으로 받는 키 목록"으로 넘어가지 않는다.
 *
 * <ul>
 *   <li><b>주소.</b> {@code issuer-uri}·{@code jwk-set-uri} 둘 다 늘 있어야 한다(로컬도). 부트의 {@code @ConfigurationProperties} 바인딩은 풀리지 않은
 *       자리표시자를 문자열 그대로 넣으므로 <code>&#36;&#123;</code> 로 시작하는 값도 비었다고 본다. {@code issuer-uri} 가 없으면 부트의 디코더는 발급자를
 *       검사하지 않는다.
 *   <li><b>https.</b> {@link #requireHttps}(운영)인데 둘 중 하나가 {@code https://} 가 아니면 멈춘다. 평문 키 목록은 중간자가 자기 키를 내밀어 아무 토큰이나
 *       위조하게 한다.
 * </ul>
 *
 * <p>app-auth 의 {@code ClientSettingsCheck}·OpenFGA 의 {@code OpenFgaSettingsCheck} 와 같은 모양이다.
 */
@Getter
@Setter
@ConfigurationProperties("auth")
public class ResourceServerSettings {

    /** 켜면 {@code issuer-uri}·{@code jwk-set-uri} 가 {@code https://} 가 아닐 때 기동을 멈춘다. 기본은 꺼짐(로컬), 운영만 켠다. */
    private boolean requireHttps = false;

    /** 설정을 검사한다. 어긋나면 {@link IllegalStateException} — 주소 값은 문구에 싣지 않는다. */
    public void 검사한다(String issuerUri, String jwkSetUri) {
        if (비었다(issuerUri) || 비었다(jwkSetUri)) {
            throw new IllegalStateException("리소스 서버의 issuer-uri·jwk-set-uri 가 비었거나 풀리지 않은 자리표시자다");
        }
        if (requireHttps && !(issuerUri.startsWith("https://") && jwkSetUri.startsWith("https://"))) {
            throw new IllegalStateException("auth.require-https 인데 issuer-uri·jwk-set-uri 가 https:// 가 아니다");
        }
    }

    private static boolean 비었다(String 주소) {
        return 주소 == null || 주소.isBlank() || 주소.startsWith("${");
    }
}

package dev.starryeye.authserver;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app-auth 의 {@code auth.*} 설정(설계 2026-10-28 §3.3·§3.5, A12). 클라이언트·발급자·토큰 수명은 부트의
 * {@code spring.security.oauth2.authorizationserver.*} 가 받고, 여기에는 서명 키와 운영이 켜는 요구들만 둔다. 요구는 셋 다 기본이 꺼져 있고
 * {@code auth-server-prod.yml} 이 켠다.
 */
@Getter
@Setter
@ConfigurationProperties("auth")
public class AuthServerProperties {

    /**
     * 서명용 RSA 개인 키, PKCS#8 PEM({@code -----BEGIN PRIVATE KEY-----}). 운영은 환경 변수 {@code AUTH_SIGNING_KEY} 로 준다. 없으면 기동마다 RSA 2048 을
     * 새로 만든다(로컬) — 재시작하면 그 전 토큰은 모두 무효이고, 여러 대로 띄우면 서로의 토큰을 거절한다.
     */
    private String signingKey;

    /** 켜면 {@link #signingKey} 가 비었거나 풀리지 않은 자리표시자일 때 기동을 멈춘다. */
    private boolean requireSigningKey = false;

    /** 켜면 {@code {bcrypt}} 해시가 아닌 클라이언트 비밀이 있을 때 기동을 멈춘다 — 평문 비밀은 클라이언트(IdP·운영 도구) 쪽에만 둔다. */
    private boolean requireHashedSecrets = false;

    /** 켜면 발급자가 {@code https://} 가 아닐 때 기동을 멈춘다. */
    private boolean requireHttps = false;
}

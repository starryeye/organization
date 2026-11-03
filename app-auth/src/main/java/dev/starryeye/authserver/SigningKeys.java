package dev.starryeye.authserver;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * 서명 키(설계 2026-10-28 §3.3·§3.4). 부트가 기동마다 만드는 {@code JWKSource} 를 이 빈으로 바꾼다 — 키는 하나다.
 *
 * <ul>
 *   <li><b>키가 주어지면</b>({@code auth.signing-key}, 운영은 환경 변수 {@code AUTH_SIGNING_KEY}) PKCS#8 PEM RSA 개인 키로 읽고 공개 키를 계산한다.
 *       2048비트보다 짧으면 멈춘다.
 *       {@code kid} 는 공개 키의 지문(RFC 7638, Nimbus {@link RSAKey#computeThumbprint()} 와 같은 SHA-256)이라 같은 키로 뜬 여러 대가 같은
 *       {@code kid} 를 낸다 — 발급 기록도 남기지 않으니({@link NoRetentionAuthorizationService}) app-auth 는 상태가 없다.
 *   <li><b>없으면</b> RSA 2048 을 새로 만든다(로컬·시험). 재시작하면 그 전 토큰은 모두 무효다.
 *   <li><b>{@code auth.require-signing-key}</b>(운영)인데 비었거나 풀리지 않은 자리표시자면 멈춘다. 부트의 바인딩은 풀리지 않은 자리표시자를
 *       {@code "${AUTH_SIGNING_KEY}"} 문자열 그대로 넣으므로 명시적으로 본다. 키는 오류 문구에 싣지 않는다.
 * </ul>
 *
 * <p>키를 바꾸면 그 전 토큰은 리소스 서버가 새 키 목록을 받는 순간 모두 무효다(§3.4 — 키 교체 = 전부 무효화).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
class SigningKeys {

    /** RSA 서명 키의 최소 크기(RFC 7518 §3.3 — Nimbus 서명기가 이보다 짧은 키를 거절한다). 로컬에서 만드는 키도 이 크기다. */
    private static final int 최소_비트 = 2048;

    private static final String 읽지_못함 = "auth.signing-key 를 PKCS#8 PEM RSA 개인 키(-----BEGIN PRIVATE KEY-----)로 읽지 못했다"
            + " — PKCS#1(-----BEGIN RSA PRIVATE KEY-----)이면 openssl pkcs8 -topk8 -nocrypt 로 바꾼다";

    @Bean
    JWKSource<SecurityContext> jwkSource(AuthServerProperties properties) {
        return new ImmutableJWKSet<>(new JWKSet(서명_키(properties)));
    }

    static RSAKey 서명_키(AuthServerProperties properties) {
        String 키 = properties.getSigningKey();
        if (키 == null || 키.isBlank() || 키.startsWith("${")) {
            if (properties.isRequireSigningKey()) {
                throw new IllegalStateException("auth.require-signing-key 인데 auth.signing-key 가 비었거나 풀리지 않은 자리표시자다"
                        + " — 환경 변수 AUTH_SIGNING_KEY 를 준다");
            }
            RSAKey 만든_키 = 만든다();
            log.info("서명 키를 기동 때 만들었다(auth.signing-key 없음) — 재시작하면 그 전 토큰은 모두 무효다. kid={}", 만든_키.getKeyID());
            return 만든_키;
        }
        RSAKey 읽은_키 = 읽는다(키);
        log.info("서명 키를 auth.signing-key 에서 읽었다. kid={}", 읽은_키.getKeyID());
        return 읽은_키;
    }

    /** PKCS#8 PEM 을 읽는다. 머리·꼬리 줄과 공백(줄바꿈 포함)을 걷어 낸 나머지를 Base64 로 푼다. */
    private static RSAKey 읽는다(String pem) {
        try {
            String 본문 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            var 공장 = KeyFactory.getInstance("RSA");
            if (!(공장.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(본문))) instanceof RSAPrivateCrtKey 개인_키)) {
                throw new IllegalStateException(읽지_못함);
            }
            // 짧은 키로도 기동은 되지만 서명기(Nimbus RSASSASigner)가 서명을 거절해 모든 발급이 실패한다 — 기동 때 멈춘다. 비트 수는 비밀이 아니다
            int 비트 = 개인_키.getModulus().bitLength();
            if (비트 < 최소_비트) {
                throw new IllegalStateException("auth.signing-key 의 RSA 키가 " + 비트 + "비트다 — " + 최소_비트
                        + "비트 이상이어야 한다(그보다 짧은 키로는 서명하지 않는다)");
            }
            var 공개_키 = (RSAPublicKey) 공장.generatePublic(new RSAPublicKeySpec(개인_키.getModulus(), 개인_키.getPublicExponent()));
            return 지문을_kid_로(공개_키, 개인_키);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 원인 예외(잘못된 Base64 글자·키 형식)의 문구에는 키가 실리지 않는다
            throw new IllegalStateException(읽지_못함, e);
        }
    }

    private static RSAKey 만든다() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(최소_비트);
            var 키_쌍 = generator.generateKeyPair();
            return 지문을_kid_로((RSAPublicKey) 키_쌍.getPublic(), (RSAPrivateCrtKey) 키_쌍.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey 지문을_kid_로(RSAPublicKey 공개_키, RSAPrivateCrtKey 개인_키) {
        try {
            return new RSAKey.Builder(공개_키).privateKey(개인_키).keyIDFromThumbprint().build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

}

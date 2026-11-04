package dev.starryeye.organization.security.fixture;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.starryeye.organization.security.Scopes;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 시험 토큰(설계 2026-10-28 §8.1). 시험 프로필의 리소스 서버는 {@link #공개키()} 로 검증하고({@link TestAuthAutoConfiguration}), 시험은 여기서 그 짝인 개인 키로
 * 서명한 토큰을 받는다. 클레임은 인가 서버(app-auth)가 내는 것과 같은 꼴이다 — {@code iss}·{@code sub}·{@code scope}(목록)·{@code iat}·{@code exp}.
 *
 * <p>키는 JVM 하나에서 처음 쓸 때 한 번 만든다(RSA 2048). 시험 디코더와 발급이 같은 JVM 에 있으니 그동안은 고정이다 — 개인 키를 저장소에 두지 않는다.
 * 거절 시험용으로 다른 키({@link #다른_키로_발급한다})와 발급자·만료를 바꾸는 발급({@link #발급한다(String, Instant, String, String...)})을 둔다.
 *
 * <p>범위는 낱개(가변 인자)로도 목록으로도 받는다 — {@code 발급한다("ops", Scopes.ADMIN_READ)}, {@code 발급한다("test-client", ALL_SCOPES)}.
 * {@code sub} 가 {@code null} 이면 그 클레임을 싣지 않는다.
 */
public final class TestTokens {

    /** 시험 발급자. 시험 디코더가 이 값만 받는다. */
    public static final String ISSUER = "https://auth.test";

    /** 여섯 범위 전부 — 앱 시험의 기본 머리가 싣는다. 바꿀 수 없는 목록이다(시험 여럿이 함께 쓴다). */
    public static final List<String> ALL_SCOPES = List.of(
            Scopes.SCIM, Scopes.ADMIN_READ, Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE, Scopes.DIRECTORY_LOOKUP, Scopes.ACTUATOR);

    /** 기본 수명. 규모 시험까지 한 컨텍스트가 넉넉히 쓴다. */
    private static final Duration 기본_수명 = Duration.ofHours(6);

    private static final KeyPair 시험_키 = RSA_키를_만든다();
    private static final KeyPair 다른_키 = RSA_키를_만든다();

    private TestTokens() {
    }

    /** 시험 키로 서명하고 시험 발급자, 6시간 뒤 만료. */
    public static String 발급한다(String sub, String... scopes) {
        return 발급한다(sub, List.of(scopes));
    }

    /** {@link #발급한다(String, String...)} 의 목록판 — {@link #ALL_SCOPES} 를 그대로 넘긴다. */
    public static String 발급한다(String sub, List<String> scopes) {
        return 발급한다(sub, Instant.now().plus(기본_수명), ISSUER, scopes);
    }

    /** 시험 키로 서명하되 만료와 발급자를 고른다 — 만료된 토큰, 다른 발급자의 토큰. */
    public static String 발급한다(String sub, Instant 만료, String 발급자, String... scopes) {
        return 발급한다(sub, 만료, 발급자, List.of(scopes));
    }

    /** {@link #발급한다(String, Instant, String, String...)} 의 목록판. */
    public static String 발급한다(String sub, Instant 만료, String 발급자, List<String> scopes) {
        return 서명한다(시험_키, sub, 만료, 발급자, scopes);
    }

    /** 시험 디코더가 모르는 키로 서명한다. 발급자·만료는 정상이다. */
    public static String 다른_키로_발급한다(String sub, String... scopes) {
        return 다른_키로_발급한다(sub, List.of(scopes));
    }

    /** {@link #다른_키로_발급한다(String, String...)} 의 목록판. */
    public static String 다른_키로_발급한다(String sub, List<String> scopes) {
        return 서명한다(다른_키, sub, Instant.now().plus(기본_수명), ISSUER, scopes);
    }

    /** 시험 디코더가 쓰는 공개 키. */
    public static RSAPublicKey 공개키() {
        return (RSAPublicKey) 시험_키.getPublic();
    }

    private static String 서명한다(KeyPair 키, String sub, Instant 만료, String 발급자, List<String> scopes) {
        Instant 지금 = Instant.now();
        // 발급 시각이 만료보다 늦지 않게 — 이미 만료된 토큰도 그럴듯한 꼴로 만든다
        Instant 발급 = 만료.isBefore(지금) ? 만료.minus(기본_수명) : 지금;
        JWTClaimsSet 클레임 = new JWTClaimsSet.Builder()
                .issuer(발급자)
                .subject(sub)
                .claim("scope", List.copyOf(scopes))
                .issueTime(Date.from(발급))
                .expirationTime(Date.from(만료))
                .build();
        SignedJWT 토큰 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), 클레임);
        try {
            토큰.sign(new RSASSASigner(키.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException("시험 토큰에 서명하지 못했다", e);
        }
        return 토큰.serialize();
    }

    private static KeyPair RSA_키를_만든다() {
        try {
            var 생성기 = KeyPairGenerator.getInstance("RSA");
            생성기.initialize(2048);
            return 생성기.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

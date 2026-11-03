package dev.starryeye.authserver;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서명 키(설계 2026-10-28 §3.3). 운영은 개인 키 PEM 을 {@code auth.signing-key}(환경 변수 {@code AUTH_SIGNING_KEY})로 받고, {@code kid} 는 공개 키의
 * 지문으로 고정한다 — 여러 대가 같은 키·같은 {@code kid} 로 서명한다. 이 시험의 앱은 시험이 만든 키 쌍의 PEM 으로 뜬다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.config.name=auth-server")
class SigningKeysTest {

    private static final KeyPair 키_쌍 = TestKeys.RSA_키_쌍();

    private static final String 키_문구 = "auth.require-signing-key 인데 auth.signing-key 가 비었거나 풀리지 않은 자리표시자다"
            + " — 환경 변수 AUTH_SIGNING_KEY 를 준다";

    @DynamicPropertySource
    static void 서명_키(DynamicPropertyRegistry registry) {
        registry.add("auth.signing-key", () -> TestKeys.PKCS8_PEM(키_쌍.getPrivate()));
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("주어진 PEM 으로 서명하고, kid 는 공개 키의 지문(RFC 7638)이며, 키 목록에는 그 키 하나만 있다")
    void 주어진_PEM_으로_서명하고_kid_는_지문이다() throws Exception {
        // given
        var 공개_키 = (RSAPublicKey) 키_쌍.getPublic();
        String 지문 = new RSAKey.Builder(공개_키).build().computeThumbprint().toString();
        var headers = new HttpHeaders();
        headers.setBasicAuth("ops", "ops-secret");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");

        // when
        var 응답 = rest.exchange("/oauth2/token", HttpMethod.POST, new HttpEntity<>(form, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        var 키_목록 = rest.exchange("/oauth2/jwks", HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, List<Map<String, Object>>>>() {
                });

        // then
        var 토큰 = SignedJWT.parse((String) 응답.getBody().get("access_token"));
        assertThat(토큰.verify(new RSASSAVerifier(공개_키))).isTrue();
        assertThat(토큰.getHeader().getKeyID()).isEqualTo(지문);
        var 키들 = 키_목록.getBody().get("keys");
        assertThat(키들).hasSize(1);
        assertThat(RSAKey.parse(키들.get(0)).toRSAPublicKey()).isEqualTo(공개_키);
        assertThat(키들.get(0)).containsEntry("kid", 지문).doesNotContainKeys("d", "p", "q");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "${AUTH_SIGNING_KEY}"})
    @DisplayName("키를 요구하는데 비었거나 풀리지 않은 자리표시자면 멈춘다 — 기동마다 새 키를 만들면 여러 대가 서로의 토큰을 거절한다")
    void 키를_요구하는데_없으면_멈춘다(String 키) {
        // given — ${AUTH_SIGNING_KEY} 에 환경 변수가 없으면 바인딩이 이 문자열을 그대로 넣는다
        var properties = new AuthServerProperties();
        properties.setRequireSigningKey(true);
        properties.setSigningKey(키);

        // when, then
        assertThatThrownBy(() -> SigningKeys.서명_키(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(키_문구);
    }

    @Test
    @DisplayName("PKCS#8 PEM RSA 개인 키가 아니면 무엇을 줘야 하는지 말하며 멈춘다")
    void PKCS8_PEM_RSA_개인_키가_아니면_멈춘다() {
        // given — EC 키(PKCS#8 이지만 RSA 가 아님)와 PEM 이 아닌 값
        String EC_키 = TestKeys.PKCS8_PEM(TestKeys.키_쌍("EC", 256).getPrivate());
        for (String 키 : List.of(EC_키, "not-a-pem-value")) {
            var properties = new AuthServerProperties();
            properties.setSigningKey(키);

            // when, then
            assertThatThrownBy(() -> SigningKeys.서명_키(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("auth.signing-key 를 PKCS#8 PEM RSA 개인 키(-----BEGIN PRIVATE KEY-----)로 읽지 못했다"
                            + " — PKCS#1(-----BEGIN RSA PRIVATE KEY-----)이면 openssl pkcs8 -topk8 -nocrypt 로 바꾼다");
        }
    }
}

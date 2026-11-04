package dev.starryeye.organization.security.fixture;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * 시험 프로필의 인증(설계 2026-10-28 §8.1). {@code test.auth.fixed-key=true} 일 때만 켜진다 — 두 앱의 {@code application-test.yml} 이 켠다.
 *
 * <ul>
 *   <li><b>시험 디코더.</b> 인가 서버의 키 목록 대신 {@link TestTokens#공개키()} 로 검증한다. 발급자 검증은 운영과 같다({@link TestTokens#ISSUER} 만).
 *       부트의 리소스 서버 자동 구성은 {@link ReactiveJwtDecoder} 빈이 없을 때만 키 목록 디코더를 만드므로, 이 구성이 그보다 <b>먼저</b> 등록돼야 물러난다
 *       ({@code before}). 보안 체인은 운영과 같은 것을 쓴다 — 바뀌는 것은 키를 어디서 받느냐뿐이다.
 *   <li><b>기본 머리.</b> 실행 중인 서버에 묶인 {@code WebTestClient} 가 일곱 범위를 모두 가진 {@code test-client} 토큰을 {@code Authorization} 으로 싣는다.
 *       {@code client.mutate()} 로 만든 클라이언트도 이어받는다. 토큰 없는 요청은 {@code mutate().defaultHeaders(h -> h.remove(AUTHORIZATION))}, 다른
 *       토큰은 요청마다 {@code header(AUTHORIZATION, …)} 로 덮어쓴다.
 * </ul>
 */
@AutoConfiguration(before = ReactiveOAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnProperty(name = "test.auth.fixed-key", havingValue = "true")
public class TestAuthAutoConfiguration {

    /** 기본 머리의 클라이언트 — 이 토큰으로 온 요청의 행위자는 {@code client:test-client} 다. */
    public static final String 시험_클라이언트 = "test-client";

    @Bean
    ReactiveJwtDecoder 시험_디코더() {
        NimbusReactiveJwtDecoder 디코더 = NimbusReactiveJwtDecoder.withPublicKey(TestTokens.공개키()).build();
        디코더.setJwtValidator(JwtValidators.createDefaultWithIssuer(TestTokens.ISSUER));
        return 디코더;
    }

    @Bean
    WebTestClientBuilderCustomizer 모든_범위_토큰을_싣는다() {
        return builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestTokens.발급한다(시험_클라이언트, TestTokens.ALL_SCOPES));
    }
}

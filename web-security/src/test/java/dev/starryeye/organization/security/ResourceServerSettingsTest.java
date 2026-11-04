package dev.starryeye.organization.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리소스 서버의 기동 검사(설계 2026-10-28 A12). 두 주소({@code issuer-uri}·{@code jwk-set-uri})는 늘 있어야 하고, 운영({@code auth.require-https})은
 * 둘 다 {@code https://} 여야 한다. 마지막 시험은 자동 구성으로 컨텍스트를 띄워 검사가 보안 체인을 만들기 전에 기동을 멈추는지 본다.
 */
class ResourceServerSettingsTest {

    private static final String 비었음 = "리소스 서버의 issuer-uri·jwk-set-uri 가 비었거나 풀리지 않은 자리표시자다";
    private static final String 평문 = "auth.require-https 인데 issuer-uri·jwk-set-uri 가 https:// 가 아니다";

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "null, https://auth.example.com/oauth2/jwks",
            "'', https://auth.example.com/oauth2/jwks",
            "'   ', https://auth.example.com/oauth2/jwks",
            "${AUTH_ISSUER_URI}, https://auth.example.com/oauth2/jwks",
            "https://auth.example.com, null",
            "https://auth.example.com, ''",
            "https://auth.example.com, ${AUTH_JWK_SET_URI}"})
    @DisplayName("issuer-uri·jwk-set-uri 중 하나라도 비었거나 풀리지 않은 자리표시자면 멈춘다 — https 를 요구하지 않아도(로컬)")
    void 주소가_비었거나_자리표시자면_멈춘다(String issuerUri, String jwkSetUri) {
        // given — ${AUTH_ISSUER_URI} 에 환경 변수가 없으면 바인딩이 이 문자열을 그대로 넣는다
        var 설정 = new ResourceServerSettings();

        // when, then
        assertThatThrownBy(() -> 설정.검사한다(issuerUri, jwkSetUri))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(비었음);
    }

    @ParameterizedTest
    @CsvSource({
            "http://auth.example.com, https://auth.example.com/oauth2/jwks",
            "https://auth.example.com, http://auth.example.com/oauth2/jwks"})
    @DisplayName("https 를 요구하면 둘 중 하나라도 https:// 가 아니면 멈춘다. 요구하지 않으면(로컬) http 를 받고, 둘 다 https 면 통과한다")
    void https_를_요구하면_https_가_아닌_주소는_멈춘다(String issuerUri, String jwkSetUri) {
        // given
        var 요구함 = new ResourceServerSettings();
        요구함.setRequireHttps(true);

        // when, then
        assertThatThrownBy(() -> 요구함.검사한다(issuerUri, jwkSetUri))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(평문);
        assertThatCode(() -> new ResourceServerSettings().검사한다(issuerUri, jwkSetUri)).doesNotThrowAnyException();
        assertThatCode(() -> 요구함.검사한다("https://auth.example.com", "https://auth.example.com/oauth2/jwks"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("자동 구성으로 띄우면 보안 체인을 만들기 전에 검사가 기동을 멈춘다 — 운영 설정(auth.require-https)도 받는다")
    void 검사가_기동을_멈춘다() {
        // given — 부트의 보안·리소스 서버 자동 구성과 함께
        var 앱 = new ReactiveWebApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                ResourceServerSecurityConfig.class, ReactiveOAuth2ResourceServerAutoConfiguration.class,
                ReactiveSecurityAutoConfiguration.class));

        // when, then — 둘 다 없다. 부트는 디코더를 만들지 않는다 — "디코더 빈이 없다" 가 아니라 이 문구로 멈춰야 한다
        앱.run(컨텍스트 -> assertThat(컨텍스트).getFailure().rootCause().hasMessage(비었음));
        // when, then — issuer-uri 가 없다
        앱.withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example.com/oauth2/jwks")
                .run(컨텍스트 -> assertThat(컨텍스트).getFailure().rootCause().hasMessage(비었음));
        // when, then — 운영인데 평문
        앱.withPropertyValues(
                        "auth.require-https=true",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://auth.example.com",
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://auth.example.com/oauth2/jwks")
                .run(컨텍스트 -> assertThat(컨텍스트).getFailure().rootCause().hasMessage(평문));
        // when, then — 둘 다 https 면 뜬다(키 목록에는 묻지 않는다)
        앱.withPropertyValues(
                        "auth.require-https=true",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.example.com",
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example.com/oauth2/jwks")
                .run(컨텍스트 -> assertThat(컨텍스트).hasNotFailed());
    }
}

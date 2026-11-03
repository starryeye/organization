package dev.starryeye.authserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 기동 때 클라이언트·발급자 설정을 검사한다(설계 2026-10-28 §3.2·§3.5, A12). 부트가 {@code spring.security.oauth2.authorizationserver.*} 로 받은
 * 클라이언트를 본다. 앞의 세 시험은 검사만 부르고, 마지막 시험은 운영 프로필로 앱을 띄워 {@code auth-server-prod.yml} 의 요구가 기동을 멈추는지 본다.
 */
class ClientSettingsCheckTest {

    private static final String BCRYPT_비밀 = "{bcrypt}" + new BCryptPasswordEncoder().encode("scim-idp-secret");

    private static OAuth2AuthorizationServerProperties 인가_서버(Duration 수명, String 비밀) {
        var 인가_서버 = new OAuth2AuthorizationServerProperties();
        인가_서버.setIssuer("https://auth.example.com");
        var 클라이언트 = new OAuth2AuthorizationServerProperties.Client();
        클라이언트.getRegistration().setClientId("scim-idp");
        클라이언트.getRegistration().setClientSecret(비밀);
        클라이언트.getToken().setAccessTokenTimeToLive(수명);
        인가_서버.getClient().put("idp", 클라이언트);
        return 인가_서버;
    }

    @Test
    @DisplayName("토큰 수명이 365일을 넘으면 멈춘다 — RFC 7644 §7 은 bearer 토큰의 수명이 유한하길 요구한다. 365일 꼭 맞으면 통과한다")
    void 수명이_365일을_넘으면_멈춘다() {
        // given — 붙여 넣는 토큰(Okta HTTP Header)용 클라이언트는 수명을 길게 준다
        var 넘음 = 인가_서버(Duration.ofDays(365).plusSeconds(1), "{noop}scim-idp-secret");
        var 꼭_맞음 = 인가_서버(Duration.ofDays(365), "{noop}scim-idp-secret");

        // when, then
        assertThatThrownBy(() -> ClientSettingsCheck.검사한다(넘음, new AuthServerProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("클라이언트 scim-idp 의 토큰 수명이 365일을 넘는다 — RFC 7644 §7");
        assertThatCode(() -> ClientSettingsCheck.검사한다(꼭_맞음, new AuthServerProperties())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("해시를 요구하면 {bcrypt} 가 아닌 비밀(noop 평문)은 멈추고, 오류 문구에 비밀을 싣지 않는다. 요구하지 않으면(로컬) 받는다")
    void 해시를_요구하면_noop_비밀은_멈춘다() {
        // given
        var 요구함 = new AuthServerProperties();
        요구함.setRequireHashedSecrets(true);
        var 평문 = 인가_서버(Duration.ofMinutes(60), "{noop}scim-idp-secret");
        var 해시 = 인가_서버(Duration.ofMinutes(60), BCRYPT_비밀);

        // when, then
        assertThatThrownBy(() -> ClientSettingsCheck.검사한다(평문, 요구함))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("클라이언트 scim-idp 의 비밀이 {bcrypt} 해시가 아니다");
        assertThatCode(() -> ClientSettingsCheck.검사한다(해시, 요구함)).doesNotThrowAnyException();
        assertThatCode(() -> ClientSettingsCheck.검사한다(평문, new AuthServerProperties())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"http://localhost:9000", "${AUTH_ISSUER}"})
    @DisplayName("https 를 요구하면 https:// 가 아닌 발급자(http·없음·풀리지 않은 자리표시자)는 멈춘다")
    void https_를_요구하면_http_발급자는_멈춘다(String 발급자) {
        // given — ${AUTH_ISSUER} 에 환경 변수가 없으면 바인딩이 이 문자열을 그대로 넣는다
        var 요구함 = new AuthServerProperties();
        요구함.setRequireHttps(true);
        var 인가_서버 = 인가_서버(Duration.ofMinutes(10), BCRYPT_비밀);
        인가_서버.setIssuer(발급자);

        // when, then
        assertThatThrownBy(() -> ClientSettingsCheck.검사한다(인가_서버, 요구함))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auth.require-https 인데 발급자가 https:// 가 아니다");
    }

    static Stream<Arguments> 운영에_빠진_것() {
        String 서명_키 = TestKeys.PKCS8_PEM(TestKeys.RSA_키_쌍().getPrivate());
        return Stream.of(
                Arguments.of("서명 키", 운영_인자("https://auth.example.com", BCRYPT_비밀, null),
                        "auth.require-signing-key 인데 auth.signing-key 가 비었거나 풀리지 않은 자리표시자다 — 환경 변수 AUTH_SIGNING_KEY 를 준다"),
                Arguments.of("bcrypt 비밀", 운영_인자("https://auth.example.com", "{noop}ops-secret", 서명_키),
                        "클라이언트 ops 의 비밀이 {bcrypt} 해시가 아니다"),
                Arguments.of("https 발급자", 운영_인자("http://auth.example.com", BCRYPT_비밀, 서명_키),
                        "auth.require-https 인데 발급자가 https:// 가 아니다"),
                // 풀리지 않은 ${AUTH_ISSUER} 는 URL 이 아니다 — Spring Authorization Server 의 "issuer must be a valid URL" 보다 먼저 이 문구로 멈춘다
                Arguments.of("발급자 환경 변수", 운영_인자(null, BCRYPT_비밀, 서명_키),
                        "auth.require-https 인데 발급자가 https:// 가 아니다"));
    }

    /**
     * 운영 프로필 인자. 클라이언트와 발급자·서명 키 환경 값을 명령줄로 준다 — 자리표시자 {@code ${AUTH_ISSUER}} 등은 명령줄 값으로도 풀린다. 환경 값이
     * {@code null} 이면 주지 않는다(자리표시자가 풀리지 않는다).
     */
    private static List<String> 운영_인자(String 발급자, String 비밀, String 서명_키) {
        String 클라이언트 = "--spring.security.oauth2.authorizationserver.client.ops.";
        var 인자 = new ArrayList<>(List.of(
                "--spring.profiles.active=prod",
                "--server.port=0",
                "--server.address=127.0.0.1",
                클라이언트 + "registration.client-id=ops",
                클라이언트 + "registration.client-secret=" + 비밀,
                클라이언트 + "registration.client-authentication-methods=client_secret_basic",
                클라이언트 + "registration.authorization-grant-types=client_credentials",
                클라이언트 + "registration.scopes=admin.read"));
        if (발급자 != null) {
            인자.add("--AUTH_ISSUER=" + 발급자);
        }
        if (서명_키 != null) {
            인자.add("--AUTH_SIGNING_KEY=" + 서명_키);
        }
        return 인자;
    }

    @ParameterizedTest(name = "[{index}] {0} 이 빠짐")
    @MethodSource("운영에_빠진_것")
    @DisplayName("운영 프로필은 서명 키·bcrypt 비밀·https 발급자를 요구한다 — 하나라도 빠지면 기동을 멈춘다")
    void 운영_프로필은_서명_키와_bcrypt_비밀과_https_발급자를_요구한다(String 빠진_것, List<String> 인자, String 문구) {
        // when — 뜨면 바로 닫는다
        Throwable 실패 = catchThrowable(() -> AuthServerApplication.애플리케이션().run(인자.toArray(String[]::new)).close());

        // then
        assertThat(실패).as(빠진_것).isNotNull()
                .rootCause().isInstanceOf(IllegalStateException.class).hasMessage(문구);
    }
}

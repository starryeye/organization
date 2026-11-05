package dev.starryeye.authserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerProperties;
import org.springframework.boot.logging.LoggingSystemProperty;
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
 * 클라이언트를 본다. 앞의 시험들은 검사만 부르고, 끝의 두 시험은 앱을 띄워 본다 — 운영 프로필이면 {@code auth-server-prod.yml} 의 요구가, 이름만 다른
 * 프로필({@code production})이면 https 발급자와 개발 클라이언트의 조합이 기동을 멈추는지.
 */
class ClientSettingsCheckTest {

    private static final String BCRYPT_비밀 = "{bcrypt}" + new BCryptPasswordEncoder().encode("scim-idp-secret");

    private static OAuth2AuthorizationServerProperties 인가_서버(Duration 수명, String 비밀) {
        var 인가_서버 = new OAuth2AuthorizationServerProperties();
        // 로컬 발급자 — {noop} 비밀을 받는 유일한 발급자다. https 발급자를 보는 시험은 따로 바꾼다
        인가_서버.setIssuer("http://localhost:9000");
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
    @ValueSource(strings = {"https://auth.example.com", "HTTPS://auth.example.com"})
    @DisplayName("발급자가 https:// 면 해시를 요구하지 않아도(prod 가 아닌 프로필) {bcrypt} 가 아닌 비밀은 멈추고, 오류 문구에 클라이언트 아이디만 싣는다")
    void 발급자가_https_면_해시를_요구하지_않아도_noop_비밀은_멈춘다(String 발급자) {
        // given — 프로필 이름이 prod 가 아니면(production 등) 개발 클라이언트({noop} 공개 비밀)가 남는다. 발급자만 https 로 주면 그 클라이언트가
        // 진짜 토큰을 낸다 — ops-break-glass 는 admin.destructive 까지
        var 요구하지_않음 = new AuthServerProperties();
        var 평문 = 인가_서버(Duration.ofMinutes(10), "{noop}scim-idp-secret");
        평문.setIssuer(발급자);
        var 해시 = 인가_서버(Duration.ofMinutes(10), BCRYPT_비밀);
        해시.setIssuer(발급자);

        // when, then
        assertThatThrownBy(() -> ClientSettingsCheck.검사한다(평문, 요구하지_않음))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("발급자가 https:// 인데 클라이언트 scim-idp 의 비밀이 {bcrypt} 해시가 아니다"
                        + " — {noop} 비밀(개발 클라이언트)은 http 발급자(로컬)에서만 받는다");
        assertThatCode(() -> ClientSettingsCheck.검사한다(해시, 요구하지_않음)).doesNotThrowAnyException();
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
                // 일부러 멈추는 기동이다 — 실패 보고(스택 수백 줄)와 배너를 찍지 않는다. 멈춘 까닭은 시험이 예외로 본다
                "--logging.level.root=OFF",
                "--spring.main.banner-mode=off",
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

    /**
     * 운영 프로필로 띄우고 그 실패를 돌려준다. 운영 설정의 ECS 로그 형식은 부트가 JVM 시스템 속성({@code CONSOLE_LOG_STRUCTURED_FORMAT})으로 남겨 같은
     * JVM 의 뒤따르는 시험 로그까지 JSON 으로 바꾸므로, 띄운 뒤 그 속성을 되돌린다.
     */
    private static Throwable 운영으로_띄운다(List<String> 인자) {
        String 형식_속성 = LoggingSystemProperty.CONSOLE_STRUCTURED_FORMAT.getEnvironmentVariableName();
        String 이전_형식 = System.getProperty(형식_속성);
        try {
            return catchThrowable(() -> AuthServerApplication.애플리케이션().run(인자.toArray(String[]::new)).close());
        } finally {
            if (이전_형식 == null) {
                System.clearProperty(형식_속성);
            } else {
                System.setProperty(형식_속성, 이전_형식);
            }
        }
    }

    @Test
    @DisplayName("prod 가 아닌 이름의 프로필(production)에 https 발급자를 주면 개발 클라이언트 때문에 기동을 멈춘다 — 부트의 환경 변수로 발급자만 바꾼 경우")
    void prod_가_아닌_프로필에_https_발급자를_주면_개발_클라이언트_때문에_멈춘다() {
        // given — production 은 prod 가 아니라 auth-server.yml 의 !prod 문서(개발 클라이언트 여섯)가 그대로 올라온다. 발급자는 부트 자신의 속성
        // (SPRING_SECURITY_OAUTH2_AUTHORIZATIONSERVER_ISSUER 와 같은 것)으로 준다
        var 인자 = List.of(
                "--spring.profiles.active=production",
                "--logging.level.root=OFF",
                "--spring.main.banner-mode=off",
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.security.oauth2.authorizationserver.issuer=https://auth.example.com");

        // when
        Throwable 실패 = 운영으로_띄운다(인자);

        // then — 어느 클라이언트가 먼저 걸리든(부트가 클라이언트를 순서 없는 맵에 담는다) 아이디만 싣고 비밀(<id>-secret)은 싣지 않는다
        assertThat(실패).isNotNull()
                .rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("발급자가 https:// 인데 클라이언트 ")
                .hasMessageNotContaining("-secret");
    }

    @ParameterizedTest(name = "[{index}] {0} 이 빠짐")
    @MethodSource("운영에_빠진_것")
    @DisplayName("운영 프로필은 서명 키·bcrypt 비밀·https 발급자를 요구한다 — 하나라도 빠지면 기동을 멈춘다")
    void 운영_프로필은_서명_키와_bcrypt_비밀과_https_발급자를_요구한다(String 빠진_것, List<String> 인자, String 문구) {
        // when — 뜨면 바로 닫는다
        Throwable 실패 = 운영으로_띄운다(인자);

        // then
        assertThat(실패).as(빠진_것).isNotNull()
                .rootCause().isInstanceOf(IllegalStateException.class).hasMessage(문구);
    }
}

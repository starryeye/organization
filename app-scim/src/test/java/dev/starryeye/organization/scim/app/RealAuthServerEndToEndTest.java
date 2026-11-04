package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.starryeye.authserver.AuthServerApplication;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.scim.ScimSchemas;
import dev.starryeye.organization.security.Scopes;
import dev.starryeye.organization.storage.DynamoDbProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.netty.NettyWebServer;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 인가 서버로 하는 시험(설계 2026-10-28 §8.2). app-auth 를 같은 JVM 에서 무작위 포트로 띄우고, app-scim 을 그 키 목록({@code jwk-set-uri})과
 * 발급자로 띄운다. 시험 디코더({@code test.auth.fixed-key})를 끄므로 토큰은 운영과 같은 길로 검증된다 — 키 목록을 받아 서명을, 그리고 발급자·만료를 본다.
 * 시험 디코더와 함께 기본 머리(모든 범위의 {@code test-client} 토큰)도 꺼지므로 요청마다 app-auth 에서 client credentials 로 받은 토큰을 싣는다.
 * 클라이언트는 개발 클라이언트({@code auth-server.yml} 의 {@code !prod} 문서) 중 둘이다 — {@code scim-idp}({@code scim}), {@code ops}
 * ({@code admin.read admin.sync}). 발급자가 https 라 개발 클라이언트의 {@code {noop}} 비밀로는 app-auth 가 뜨지 않으므로(app-auth 의
 * {@code ClientSettingsCheck}) 다섯 모두의 비밀을 같은 평문의 bcrypt 해시로 바꿔 띄운다({@link #해시한_비밀_인자}).
 *
 * <p><b>한 클래스패스에 두 앱.</b> app-auth(서블릿)는 이 모듈의 시험 클래스패스에만 올라온다.
 * <ul>
 *   <li>웹 앱 종류와 서버 — app-scim 은 서블릿 클래스가 있어도 {@code application.yml} 의 {@code spring.main.web-application-type: reactive} 로
 *       리액티브로 뜨고, 부트가 Netty 보다 먼저 고르는 Tomcat 대신 운영처럼 Netty 위에 뜬다({@link NettyServerTestAutoConfiguration}). 첫 시험이 둘을
 *       확인한다. app-auth 는 {@code web(SERVLET)} 으로 Tomcat 위에 띄운다.
 *   <li>스캔과 자동 구성 — app-auth 의 패키지({@code dev.starryeye.authserver})는 app-scim 의 스캔({@code dev.starryeye.organization}) 밖이다. 거꾸로
 *       app-auth 의 자동 구성은 이 클래스패스에 있는 우리 모듈의 자동 구성(DynamoDB·OpenFGA·SCIM·관리 API·리소스 서버 …)을 모두 집어 들므로 그것들을
 *       빼고 띄운다({@link #우리_모듈의_자동_구성()}) — 운영의 app-auth 클래스패스에는 없는 것들이다.
 *   <li>설정 파일 — app-auth 는 {@code auth-server.yml} 을 읽는다({@code spring.config.name=auth-server}). app-scim 의 {@code application.yml} 과 겹치지 않는다.
 * </ul>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealAuthServerEndToEndTest {

    /**
     * app-auth 의 발급자 — 시험 발급자와 같은 이름이다(app-auth 의 시험 프로필도 이 값이다). 받는 주소(127.0.0.1:무작위 포트)와 달라도 된다: 토큰의
     * {@code iss} 는 이 값이고, app-scim 은 인가 서버에 키 목록 주소로만 묻는다.
     */
    private static final String 발급자 = "https://auth.test";

    /**
     * 개발 클라이언트 다섯({@code auth-server.yml} 의 {@code !prod} 문서)의 비밀을 같은 평문({@code <id>-secret})의 bcrypt 해시로 바꾸는 명령줄 인자.
     * 하나라도 빠지면 app-auth 가 그 아이디를 대며 뜨지 않는다 — 개발 클라이언트를 더하면 여기에도 더한다. 띄울 때마다 해시하지 않게 한 번 만든다.
     */
    private static final List<String> 해시한_비밀_인자 = Stream.of("scim-idp", "ops", "ops-break-glass", "asking-app", "prometheus")
            .map(아이디 -> "--spring.security.oauth2.authorizationserver.client." + 아이디 + ".registration.client-secret={bcrypt}"
                    + new BCryptPasswordEncoder().encode(아이디 + "-secret"))
            .toList();

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    /** 띄운 app-auth. 다시 띄우는 시험이 바꾼다 */
    private static ConfigurableApplicationContext 인가_서버;
    /** 처음 띄울 때 받은 무작위 포트. 다시 띄울 때도 이 포트다 — app-scim 의 키 목록 주소가 이 포트로 정해져 있다 */
    private static int 인가_서버_포트;

    @BeforeAll
    static void 인가_서버를_띄운다() {
        인가_서버 = 띄운다(0);
        인가_서버_포트 = ((WebServerApplicationContext) 인가_서버).getWebServer().getPort();
    }

    @AfterAll
    static void 인가_서버를_내린다() {
        if (인가_서버 != null) {
            인가_서버.close();
        }
    }

    @DynamicPropertySource
    static void 주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
        // 시험 디코더를 끄고 운영과 같은 키 목록 디코더를 쓴다 — 키 목록과 발급자는 위에서 띄운 app-auth 다
        registry.add("test.auth.fixed-key", () -> "false");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> 발급자);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> 인가_서버_주소() + "/oauth2/jwks");
    }

    @Autowired WebTestClient client;
    @Autowired DynamoDbProperties dynamoDb;
    @Autowired ApplicationContext context;

    /**
     * app-auth 를 띄운다. 빌더의 기본값({@code properties(…)})은 {@code auth-server.yml} 에 지므로 yml 에 있는 것(포트 9000·발급자·클라이언트 비밀)을 바꾸려면 명령줄 인자로
     * 준다. 서명 키({@code auth.signing-key})를 주지 않으므로 띄울 때마다 키를 새로 만든다.
     */
    private static ConfigurableApplicationContext 띄운다(int 포트) {
        var 인자 = new ArrayList<>(List.of("--server.port=" + 포트,
                // app-scim 의 시험 서버와 같은 까닭으로 127.0.0.1 에만 연다(application-test.yml)
                "--server.address=127.0.0.1",
                "--spring.security.oauth2.authorizationserver.issuer=" + 발급자,
                "--spring.autoconfigure.exclude=" + 우리_모듈의_자동_구성()));
        인자.addAll(해시한_비밀_인자);
        return AuthServerApplication.애플리케이션()
                .web(WebApplicationType.SERVLET)
                .run(인자.toArray(String[]::new));
    }

    /** 이 시험 클래스패스에 올라온 우리 모듈의 자동 구성 이름들(쉼표로 잇는다). 모듈이 자동 구성을 더해도 따라온다 */
    private static String 우리_모듈의_자동_구성() {
        return ImportCandidates.load(AutoConfiguration.class, RealAuthServerEndToEndTest.class.getClassLoader())
                .getCandidates().stream()
                .filter(이름 -> 이름.startsWith("dev.starryeye.organization."))
                .collect(Collectors.joining(","));
    }

    private static String 인가_서버_주소() {
        return "http://127.0.0.1:" + 인가_서버_포트;
    }

    /** client credentials 로 토큰을 받는다. 범위는 보내지 않는다 — 등록된 범위를 모두 받는다(app-auth 의 DefaultScopes). */
    private static String 토큰을_받는다(String 클라이언트, String 비밀) {
        JsonNode 응답 = WebTestClient.bindToServer().baseUrl(인가_서버_주소()).build()
                .post().uri("/oauth2/token")
                .headers(머리 -> 머리.setBasicAuth(클라이언트, 비밀))
                .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        return 응답.get("access_token").asText();
    }

    /** 이 토큰을 기본 머리로 싣는 app-scim 클라이언트. */
    private WebTestClient 싣는(String 토큰) {
        return client.mutate().defaultHeaders(머리 -> 머리.setBearerAuth(토큰)).build();
    }

    private static String 직원_본문(String userName) {
        return """
                {"schemas":["%s"],"userName":"%s","displayName":"%s","active":true}
                """.formatted(ScimSchemas.USER, userName, userName);
    }

    private static String 키_아이디(String 토큰) throws ParseException {
        return SignedJWT.parse(토큰).getHeader().getKeyID();
    }

    /**
     * 지금 띄운 app-auth 의 서명 키로 직접 서명한 {@code scim-idp} 토큰({@code scim}). 발급자만 고른다 — 나머지(키·{@code kid}·범위·만료)는 app-auth 가
     * 내는 것과 같으므로, 거절된다면 발급자 때문이다.
     */
    @SuppressWarnings("unchecked")
    private static String 인가_서버의_키로_서명한다(String iss) throws Exception {
        JWKSource<SecurityContext> 키_목록 = 인가_서버.getBean(JWKSource.class);
        RSAKey 키 = 키_목록.get(new JWKSelector(new JWKMatcher.Builder().build()), null).get(0).toRSAKey();
        Instant 지금 = Instant.now();
        JWTClaimsSet 클레임 = new JWTClaimsSet.Builder()
                .issuer(iss)
                .subject("scim-idp")
                .claim("scope", List.of(Scopes.SCIM))
                .issueTime(Date.from(지금))
                .expirationTime(Date.from(지금.plus(Duration.ofMinutes(10))))
                .build();
        SignedJWT 토큰 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(키.getKeyID()).build(), 클레임);
        토큰.sign(new RSASSASigner(키));
        return 토큰.serialize();
    }

    @Test
    @DisplayName("app-auth(서블릿·Tomcat)와 한 클래스패스여도 app-scim 은 운영처럼 리액티브로, Reactor Netty 위에 뜬다")
    void app_auth_와_한_클래스패스여도_app_scim_은_리액티브로_Netty_위에_뜬다() {
        // when, then — 웹 앱 종류는 application.yml 이, 서버는 NettyServerTestAutoConfiguration 이 정한다
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
        assertThat(((ReactiveWebServerApplicationContext) context).getWebServer()).isInstanceOf(NettyWebServer.class);
    }

    @Test
    @DisplayName("client credentials 로 받은 토큰으로 SCIM 쓰기와 관리 API 가 통과하고, SCIM 쓰기의 이력에 행위자 client:scim-idp 가 남는다")
    void client_credentials_로_받은_토큰으로_SCIM_쓰기와_관리_API_가_통과한다() {
        // given
        WebTestClient IdP = 싣는(토큰을_받는다("scim-idp", "scim-idp-secret"));
        WebTestClient 운영 = 싣는(토큰을_받는다("ops", "ops-secret"));

        // when
        String id = 만든_아이디(IdP.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(직원_본문("real-auth-kim"))
                .exchange());

        // then
        운영.get().uri("/admin/sync/runs").exchange()
                .expectStatus().isOk();
        운영.get().uri("/admin/employees/{id}/history", id).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].cause.actor").isEqualTo("client:scim-idp");
    }

    @Test
    @DisplayName("scim-idp 토큰(scim)으로 관리 API 는 403 이다")
    void scim_idp_토큰으로_관리_API_는_403_이다() {
        // given
        WebTestClient IdP = 싣는(토큰을_받는다("scim-idp", "scim-idp-secret"));

        // when
        IdP.get().uri("/admin/sync/runs").exchange()

                // then
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("이 요청에 필요한 범위가 없다");
    }

    @Test
    @DisplayName("인가 서버를 같은 포트로 다시 띄워 키가 바뀌면 옛 토큰은 401 이고 새 토큰은 200 이다 — 키 교체는 전부 무효화다(설계 §3.4)")
    void 인가_서버를_다시_띄워_키가_바뀌면_옛_토큰은_401_이다() throws Exception {
        // given — 옛 키의 토큰으로 한 번 지나가 app-scim 이 옛 키 목록을 받아 둔다
        String 옛_토큰 = 토큰을_받는다("ops", "ops-secret");
        싣는(옛_토큰).get().uri("/admin/sync/runs").exchange()
                .expectStatus().isOk();

        // when — 같은 포트로 다시 띄운다. 서명 키를 주지 않으므로 새로 만든 키다
        인가_서버.close();
        인가_서버 = 띄운다(인가_서버_포트);
        String 새_토큰 = 토큰을_받는다("ops", "ops-secret");
        assertThat(키_아이디(새_토큰)).isNotEqualTo(키_아이디(옛_토큰));

        // then — 새 토큰의 모르는 kid 를 보고 app-scim 이 키 목록을 다시 받는다(그 순간부터 옛 키는 없다). 새 토큰은 지나가고 옛 토큰은 맞는 키가 없어 401 이다
        싣는(새_토큰).get().uri("/admin/sync/runs").exchange()
                .expectStatus().isOk();
        싣는(옛_토큰).get().uri("/admin/sync/runs").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("인증이 필요하다");
    }

    @Test
    @DisplayName("범위가 맞아도 발급자가 다른 토큰은 401 이다 — 인가 서버의 키로 서명해도 운영 디코더가 발급자를 본다")
    void 발급자가_다른_토큰은_범위가_맞아도_401_이다() throws Exception {
        // given — 인가 서버의 키로 서명한 두 토큰. 발급자만 다르다
        String 맞는_발급자의_토큰 = 인가_서버의_키로_서명한다(발급자);
        String 다른_발급자의_토큰 = 인가_서버의_키로_서명한다("https://evil.test");

        // when, then — 발급자가 맞으면 지나간다: 직접 서명한 것은 거절의 까닭이 아니다
        싣는(맞는_발급자의_토큰).get().uri("/scim/v2/ServiceProviderConfig").exchange()
                .expectStatus().isOk();
        // when, then — 발급자만 다르면 401
        싣는(다른_발급자의_토큰).get().uri("/scim/v2/ServiceProviderConfig").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.status").isEqualTo("401")
                .jsonPath("$.detail").isEqualTo("인증이 필요하다");
    }

    @Test
    @DisplayName("ops 토큰(admin.destructive 없음)으로 mode=wipe 는 확인값이 맞아도 403 이고 회차가 생기지 않는다")
    void ops_토큰으로_wipe_는_403_이고_회차가_생기지_않는다() {
        // given
        WebTestClient 운영 = 싣는(토큰을_받는다("ops", "ops-secret"));
        String 전_회차 = 운영.get().uri("/admin/sync/runs").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // when
        운영.post().uri("/admin/sync/rebuild?mode=wipe&confirm=" + dynamoDb.getTableName()).exchange()

                // then
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("mode=wipe은 admin.destructive 범위가 필요하다");
        운영.get().uri("/admin/sync/runs").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(전_회차);
    }
}

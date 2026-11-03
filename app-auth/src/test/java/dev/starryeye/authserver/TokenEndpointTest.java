package dev.starryeye.authserver;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 엔드포인트와 그 곁의 공개 끝점(설계 2026-10-28 §3.1·§3.2, A1). 개발 클라이언트({@code auth-server.yml} 의 {@code !prod} 문서)로 진짜 요청을
 * 보낸다. 시험 발급자는 {@code https://auth.test} 다({@code auth-server-test.yml}).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.config.name=auth-server")
class TokenEndpointTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    private ResponseEntity<Map<String, Object>> 토큰을_받는다(String 클라이언트, String 비밀, String 범위) {
        var headers = new HttpHeaders();
        headers.setBasicAuth(클라이언트, 비밀);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        if (범위 != null) {
            form.add("scope", 범위);
        }
        return rest.exchange("/oauth2/token", HttpMethod.POST, new HttpEntity<>(form, headers), JSON);
    }

    private static SignedJWT 토큰(ResponseEntity<Map<String, Object>> 응답) throws Exception {
        return SignedJWT.parse((String) 응답.getBody().get("access_token"));
    }

    @Test
    @DisplayName("client credentials 로 JWT 를 받는다 — sub 는 클라이언트, scope 는 요청한 범위, iss 는 발급자, 수명은 클라이언트 설정(10분)")
    void client_credentials_로_JWT_를_받는다() throws Exception {
        // when
        var 응답 = 토큰을_받는다("ops", "ops-secret", "admin.read");

        // then
        assertThat(응답.getStatusCode()).isEqualTo(HttpStatus.OK);
        var 클레임 = 토큰(응답).getJWTClaimsSet();
        assertThat(클레임.getSubject()).isEqualTo("ops");
        assertThat(클레임.getStringListClaim("scope")).containsExactly("admin.read");
        assertThat(클레임.getIssuer()).isEqualTo("https://auth.test");
        assertThat(Duration.between(클레임.getIssueTime().toInstant(), 클레임.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    @DisplayName("범위를 보내지 않으면 등록된 범위를 모두 받는다 — RFC 6749 §3.3 의 기본 범위, 범위 칸이 없는 IdP 화면")
    void 범위를_보내지_않으면_등록된_범위를_모두_받는다() throws Exception {
        // when
        var 응답 = 토큰을_받는다("ops", "ops-secret", null);

        // then
        assertThat(응답.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(토큰(응답).getJWTClaimsSet().getStringListClaim("scope"))
                .containsExactlyInAnyOrder("admin.read", "admin.sync");
        assertThat(((String) 응답.getBody().get("scope")).split(" ")).containsExactlyInAnyOrder("admin.read", "admin.sync");
    }

    @Test
    @DisplayName("등록되지 않은 범위는 invalid_scope 로 거절한다 — ops 는 admin.destructive 를 받지 못한다")
    void 등록되지_않은_범위는_거절한다() {
        // when
        var 응답 = 토큰을_받는다("ops", "ops-secret", "admin.read admin.destructive");

        // then
        assertThat(응답.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(응답.getBody()).containsEntry("error", "invalid_scope");
    }

    @Test
    @DisplayName("틀린 비밀은 401 invalid_client 다")
    void 틀린_비밀은_401_이다() {
        // when
        var 응답 = 토큰을_받는다("ops", "wrong-secret", "admin.read");

        // then
        assertThat(응답.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(응답.getBody()).containsEntry("error", "invalid_client");
    }

    @Test
    @DisplayName("키 목록과 메타데이터는 인증 없이 열린다 — 키는 하나이고 그 kid 가 토큰 머리의 kid 다")
    void 키_목록과_메타데이터가_열린다() throws Exception {
        // given
        String 토큰_kid = 토큰(토큰을_받는다("ops", "ops-secret", "admin.read")).getHeader().getKeyID();

        // when
        var 키_목록 = rest.exchange("/oauth2/jwks", HttpMethod.GET, null, JSON);
        var 메타데이터 = rest.exchange("/.well-known/oauth-authorization-server", HttpMethod.GET, null, JSON);

        // then
        assertThat(키_목록.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        var 키들 = (List<Map<String, Object>>) 키_목록.getBody().get("keys");
        assertThat(키들).hasSize(1);
        assertThat(키들.get(0)).containsEntry("kid", 토큰_kid);
        assertThat(메타데이터.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(메타데이터.getBody())
                .containsEntry("issuer", "https://auth.test")
                .containsEntry("token_endpoint", "https://auth.test/oauth2/token");
    }

    @Test
    @DisplayName("프로브는 인증 없이 열리고, 그 밖의 경로(actuator 나머지·로그인 화면·아무 경로)는 거절한다")
    void 프로브는_인증_없이_열리고_그_밖은_거절한다() {
        // when, then
        for (String 프로브 : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            assertThat(rest.getForEntity(프로브, String.class).getStatusCode()).as(프로브).isEqualTo(HttpStatus.OK);
        }
        for (String 경로 : List.of("/actuator", "/actuator/env", "/login", "/oauth2/authorize", "/anything")) {
            assertThat(rest.getForEntity(경로, String.class).getStatusCode().value()).as(경로).isIn(401, 403);
        }
    }

    @Test
    @DisplayName("세션을 만들지 않는다 — 거절한 요청도 서버 메모리에 세션을 남기지 않는다(여러 대로 띄울 수 있는 무상태)")
    void 세션을_만들지_않는다() {
        // when
        var 발급 = 토큰을_받는다("ops", "ops-secret", "admin.read");
        var 거절 = rest.getForEntity("/anything", String.class);
        var 거절한_POST = rest.postForEntity("/anything", null, String.class);
        var 인증_없는_발급 = rest.postForEntity("/oauth2/token", null, String.class);

        // then
        assertThat(발급.getHeaders().get(HttpHeaders.SET_COOKIE)).as("발급").isNull();
        assertThat(거절.getHeaders().get(HttpHeaders.SET_COOKIE)).as("거절").isNull();
        assertThat(거절한_POST.getHeaders().get(HttpHeaders.SET_COOKIE)).as("거절한 POST").isNull();
        assertThat(인증_없는_발급.getHeaders().get(HttpHeaders.SET_COOKIE)).as("인증 없는 발급").isNull();
    }

    @Test
    @DisplayName("사용자 저장소를 두지 않는다 — 부트가 사용자 비밀번호를 만들어 로그에 찍지 않는다")
    void 사용자_저장소를_두지_않는다() {
        // when, then
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @Test
    @DisplayName("발급 기록을 남기지 않는다 — 발급한 토큰으로 인가 기록을 찾을 수 없다(쌓이기만 하는 메모리를 두지 않는다)")
    void 발급_기록을_남기지_않는다() throws Exception {
        // given
        var 응답 = 토큰을_받는다("ops", "ops-secret", "admin.read");
        String 토큰 = (String) 응답.getBody().get("access_token");

        // when
        var 기록 = context.getBean(OAuth2AuthorizationService.class).findByToken(토큰, OAuth2TokenType.ACCESS_TOKEN);

        // then
        assertThat(응답.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(기록).isNull();
    }

    @Test
    @DisplayName("토큰 폐기·조회 끝점은 열지 않는다 — 기록이 없어 폐기는 아무것도 못 하면서 200 을, 조회는 살아 있는 토큰에 active=false 를 답한다")
    void 토큰_폐기와_조회는_열지_않는다() {
        // given
        String 토큰 = (String) 토큰을_받는다("ops", "ops-secret", "admin.read").getBody().get("access_token");
        var headers = new HttpHeaders();
        headers.setBasicAuth("ops", "ops-secret");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", 토큰);

        // when
        var 폐기 = rest.exchange("/oauth2/revoke", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        var 조회 = rest.exchange("/oauth2/introspect", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);

        // then
        assertThat(폐기.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(조회.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

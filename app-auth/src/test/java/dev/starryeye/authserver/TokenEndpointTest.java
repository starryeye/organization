package dev.starryeye.authserver;

import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 엔드포인트와 그 곁의 공개 끝점(설계 2026-10-28 §3.1·§3.2, A1). 개발 클라이언트({@code auth-server.yml} 의 {@code !prod} 문서)로 진짜 요청을
 * 보낸다. 시험 발급자는 {@code https://auth.test} 다({@code auth-server-test.yml}) — https 발급자라 그 파일이 개발 클라이언트의 비밀을 같은 평문의
 * bcrypt 해시로 바꾼다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.config.name=auth-server")
@ExtendWith(OutputCaptureExtension.class)
class TokenEndpointTest {

    /** 시험이 서버 오류를 일으키는 머리. 이 머리가 있는 요청은 보안 필터를 지난 뒤 그 값의 상태로 {@code sendError} 한다(오류 디스패치를 탄다). */
    private static final String 오류_머리 = "X-Test-Send-Error";

    @TestConfiguration(proxyBeanMethods = false)
    static class 오류를_일으키는_필터 {

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> 오류를_일으키는_필터_등록() {
            var 필터 = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws IOException, ServletException {
                    String 상태 = request.getHeader(오류_머리);
                    if (상태 != null) {
                        response.sendError(Integer.parseInt(상태));
                        return;
                    }
                    chain.doFilter(request, response);
                }
            };
            var 등록 = new FilterRegistrationBean<OncePerRequestFilter>(필터);
            등록.setOrder(Ordered.LOWEST_PRECEDENCE); // 보안 필터 뒤
            return 등록;
        }
    }

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
    @DisplayName("메타데이터는 쓸 수 있는 것만 알린다 — client credentials 와 비밀 인증 방식, 토큰·키 목록. 조회·폐기·디바이스 끝점과 다른 grant 는 없다")
    void 메타데이터는_쓸_수_있는_것만_알린다() {
        // when
        var 메타데이터 = rest.exchange("/.well-known/oauth-authorization-server", HttpMethod.GET, null, JSON).getBody();

        // then — authorization_endpoint·response_types_supported 는 Spring Authorization Server 의 메타데이터 빌더가 반드시 요구해 남는다
        assertThat(메타데이터).containsOnlyKeys("issuer", "token_endpoint", "jwks_uri", "grant_types_supported",
                "token_endpoint_auth_methods_supported", "authorization_endpoint", "response_types_supported");
        assertThat(메타데이터)
                .containsEntry("jwks_uri", "https://auth.test/oauth2/jwks")
                .containsEntry("grant_types_supported", List.of("client_credentials"))
                .containsEntry("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
    }

    @Test
    @DisplayName("프로브는 인증 없이 열리고, 그 밖의 경로(actuator 나머지·로그인 화면·아무 경로)는 거절한다")
    void 프로브는_인증_없이_열리고_그_밖은_거절한다() {
        // when, then
        for (String 프로브 : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            assertThat(rest.getForEntity(프로브, String.class).getStatusCode()).as(프로브).isEqualTo(HttpStatus.OK);
        }
        for (String 경로 : List.of("/actuator", "/actuator/env", "/login", "/error", "/anything")) {
            assertThat(rest.getForEntity(경로, String.class).getStatusCode().value()).as(경로).isIn(401, 403);
        }
    }

    @Test
    @DisplayName("쓰지 않는 인가 끝점(인가 코드·디바이스)은 인가 서버에 닿기 전에 403 으로 거절하고, 서버 오류 로그를 남기지 않는다")
    void 쓰지_않는_인가_끝점은_인가_서버에_닿기_전에_거절한다(CapturedOutput 출력) {
        // given — 클라이언트 아이디는 비밀이 아니다. 인가 서버에 닿으면 리다이렉트 URI 가 없는 클라이언트라 NoSuchElementException 이 나고
        // 요청마다 Servlet.service() 오류 로그가 백 줄 넘게 찍혔다
        var 클라이언트_인증 = new HttpHeaders();
        클라이언트_인증.setBasicAuth("ops", "ops-secret");
        클라이언트_인증.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var 디바이스_요청 = new LinkedMultiValueMap<String, String>();
        디바이스_요청.add("client_id", "ops");
        디바이스_요청.add("scope", "admin.read");

        // when
        var 인가 = rest.getForEntity("/oauth2/authorize?response_type=code&client_id=ops&scope=admin.read", String.class);
        var 인가_POST = rest.postForEntity("/oauth2/authorize?response_type=code&client_id=scim-idp", null, String.class);
        var 디바이스_인가 = rest.exchange("/oauth2/device_authorization", HttpMethod.POST,
                new HttpEntity<>(디바이스_요청, 클라이언트_인증), String.class);
        var 디바이스_확인 = rest.getForEntity("/oauth2/device_verification?user_code=ABCD-EFGH", String.class);

        // then
        assertThat(출력.getAll()).doesNotContain("Servlet.service()").doesNotContain("ERROR");
        assertThat(List.of(인가, 인가_POST, 디바이스_인가, 디바이스_확인))
                .extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("서버 오류는 403 으로 바뀌지 않는다 — 오류 디스패치(/error)는 열고, /error 를 직접 부르는 것은 거절한다")
    void 서버_오류는_403_으로_바뀌지_않는다() {
        // given
        var headers = new HttpHeaders();
        headers.set(오류_머리, "503");

        // when
        var 오류 = rest.exchange("/actuator/health/liveness", HttpMethod.GET, new HttpEntity<>(headers), JSON);
        var 직접 = rest.getForEntity("/error", String.class);

        // then — IdP 는 403 을 권한 문제로 읽는다
        assertThat(오류.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(오류.getBody()).containsEntry("status", 503);
        assertThat(직접.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("세션을 만들지 않는다 — 거절한 요청도 서버 메모리에 세션을 남기지 않는다(여러 대로 띄울 수 있는 무상태)")
    void 세션을_만들지_않는다() {
        // when
        var 발급 = 토큰을_받는다("ops", "ops-secret", "admin.read");
        var 거절 = rest.getForEntity("/anything", String.class);
        var 거절한_POST = rest.postForEntity("/anything", null, String.class);
        var 인증_없는_발급 = rest.postForEntity("/oauth2/token", null, String.class);

        // when — 인가 서버 체인의 끝점에 그 끝점이 받지 않는 메서드로 온 익명 요청. 인가 서버가 받는 것은 키 목록·메타데이터 GET 과 토큰 POST
        // 뿐이라 POST·PUT·DELETE 는 CSRF 검사에 걸렸고, 그 검사가 CSRF 토큰을 세션에 저장하려고 요청마다 세션(30분)을 만들었다 — 익명으로 메모리를
        // 채울 수 있었다. GET /oauth2/token 은 CSRF 를 검사하지 않는 메서드라 처음부터 세션 없이 401 이었다 — 나머지도 이제 그와 같다
        var 다른_메서드 = new LinkedHashMap<String, ResponseEntity<String>>();
        for (var 요청 : List.of(
                Map.entry(HttpMethod.POST, "/oauth2/jwks"),
                Map.entry(HttpMethod.PUT, "/oauth2/jwks"),
                Map.entry(HttpMethod.DELETE, "/oauth2/jwks"),
                Map.entry(HttpMethod.POST, "/.well-known/oauth-authorization-server"),
                Map.entry(HttpMethod.GET, "/oauth2/token"),
                Map.entry(HttpMethod.PUT, "/oauth2/token"),
                Map.entry(HttpMethod.DELETE, "/oauth2/token"))) {
            다른_메서드.put(요청.getKey() + " " + 요청.getValue(), rest.exchange(요청.getValue(), 요청.getKey(), null, String.class));
        }

        // then
        assertThat(발급.getHeaders().get(HttpHeaders.SET_COOKIE)).as("발급").isNull();
        assertThat(거절.getHeaders().get(HttpHeaders.SET_COOKIE)).as("거절").isNull();
        assertThat(거절한_POST.getHeaders().get(HttpHeaders.SET_COOKIE)).as("거절한 POST").isNull();
        assertThat(인증_없는_발급.getHeaders().get(HttpHeaders.SET_COOKIE)).as("인증 없는 발급").isNull();
        // 인증이 필요한 체인의 익명 요청이라 401 이다(인가 서버가 이 체인에 둔 진입점). CSRF 에 걸리던 때는 403 이었다
        다른_메서드.forEach((이름, 응답) -> {
            assertThat(응답.getHeaders().get(HttpHeaders.SET_COOKIE)).as(이름).isNull();
            assertThat(응답.getStatusCode()).as(이름).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
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

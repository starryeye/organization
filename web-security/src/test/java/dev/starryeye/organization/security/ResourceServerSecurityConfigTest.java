package dev.starryeye.organization.security;

import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리소스 서버 보안(설계 2026-10-28 §4.1~§4.4, A2·A3·A5·A9). 흉내 라우트만 올린 작은 앱({@link MockRoutesApplication})을 띄우고 진짜 요청을 보낸다.
 * 시험 프로필이 {@code test.auth.fixed-key=true} 라 testFixtures 의 시험 디코더가 {@link TestTokens} 의 공개 키로 검증하고, 주입받은 클라이언트는
 * 여섯 범위를 모두 가진 {@code test-client} 토큰을 기본 머리로 싣는다. 토큰 없는 요청은 그 머리를 뺀 클라이언트로, 다른 토큰은 요청마다 머리를 덮어써 보낸다.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = MockRoutesApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceServerSecurityConfigTest {

    private static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");
    private static final String SCIM_ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    private static final String 토큰_없음 = "Bearer";
    private static final String 토큰이_잘못됨 = "Bearer error=\"invalid_token\"";
    private static final String 범위_부족 = "Bearer error=\"insufficient_scope\"";
    private static final String 인증_필요 = "인증이 필요하다";
    private static final String 범위_없음 = "이 요청에 필요한 범위가 없다";

    @Autowired
    WebTestClient client;

    @Autowired
    ApplicationContext context;

    /** 기본 머리(모든 범위의 토큰)를 뺀 클라이언트 */
    private WebTestClient 토큰_없이() {
        return client.mutate().defaultHeaders(머리 -> 머리.remove(HttpHeaders.AUTHORIZATION)).build();
    }

    private static String bearer(String 토큰) {
        return "Bearer " + 토큰;
    }

    @Test
    @DisplayName("토큰 없이 SCIM 을 부르면 SCIM Error 401 이다 — application/scim+json, status \"401\", 정한 문구, WWW-Authenticate 는 error 없는 Bearer(RFC 6750 §3.1)")
    void 토큰이_없으면_SCIM_은_SCIM_Error_401_이다() {
        // when, then
        토큰_없이().get().uri("/scim/v2/Users").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 토큰_없음)
                .expectHeader().contentType(SCIM_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$.schemas.length()").isEqualTo(1)
                .jsonPath("$.schemas[0]").isEqualTo(SCIM_ERROR)
                .jsonPath("$.status").isEqualTo("401")
                .jsonPath("$.detail").isEqualTo(인증_필요);
    }

    @Test
    @DisplayName("scim 범위가 없는 토큰으로 SCIM 을 부르면 SCIM Error 403 이고 WWW-Authenticate 는 insufficient_scope 다")
    void 범위가_모자라면_SCIM_은_SCIM_Error_403_이다() {
        // given
        String 관리_읽기만 = TestTokens.발급한다("ops", Scopes.ADMIN_READ);

        // when, then
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, bearer(관리_읽기만)).exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 범위_부족)
                .expectHeader().contentType(SCIM_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$.schemas[0]").isEqualTo(SCIM_ERROR)
                .jsonPath("$.status").isEqualTo("403")
                .jsonPath("$.detail").isEqualTo(범위_없음);
    }

    @Test
    @DisplayName("admin.read 만으로 동기화를 걸면(POST) 관리 API 의 problem details 403 이다 — type about:blank, title 상태 문구, WWW-Authenticate 는 insufficient_scope")
    void 범위가_모자라면_관리_API_는_problem_403_이다() {
        // given
        String 관리_읽기만 = TestTokens.발급한다("ops", Scopes.ADMIN_READ);

        // when, then
        client.post().uri("/admin/sync/full").header(HttpHeaders.AUTHORIZATION, bearer(관리_읽기만)).exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 범위_부족)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(4)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Forbidden")
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo(범위_없음);
    }

    @Test
    @DisplayName("시험 디코더가 모르는 키로 서명한 토큰은 401 이다 — 발급자·만료·범위가 맞아도. WWW-Authenticate 는 invalid_token")
    void 다른_키로_서명한_토큰은_401_이다() {
        // given
        String 다른_키 = TestTokens.다른_키로_발급한다("test-client", TestTokens.ALL_SCOPES);

        // when, then
        client.get().uri("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, bearer(다른_키)).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 토큰이_잘못됨)
                .expectHeader().contentType(SCIM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("401")
                .jsonPath("$.detail").isEqualTo(인증_필요);
    }

    @Test
    @DisplayName("키는 같고 발급자가 다른(iss=https://evil.test) 토큰은 401 이다 — 관리 API 는 problem details, WWW-Authenticate 는 invalid_token")
    void 발급자가_다른_토큰은_401_이다() {
        // given
        String 다른_발급자 = TestTokens.발급한다("test-client", Instant.now().plus(Duration.ofHours(1)), "https://evil.test",
                TestTokens.ALL_SCOPES);

        // when, then
        client.get().uri("/admin/employees").header(HttpHeaders.AUTHORIZATION, bearer(다른_발급자)).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 토큰이_잘못됨)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(4)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.detail").isEqualTo(인증_필요);
    }

    @Test
    @DisplayName("만료된 토큰은 401 이고 WWW-Authenticate 는 error_description 없이 invalid_token 뿐이다 — 검증 실패의 세부(만료 시각 등)를 본문에도 싣지 않는다")
    void 만료된_토큰은_401_이고_invalid_token_이다() {
        // given — 시계 어긋남 허용(60초)을 넘겨 만료됐다
        String 만료됨 = TestTokens.발급한다("test-client", Instant.now().minus(Duration.ofHours(1)), TestTokens.ISSUER,
                TestTokens.ALL_SCOPES);

        // when, then
        client.get().uri("/admin/employees").header(HttpHeaders.AUTHORIZATION, bearer(만료됨)).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 토큰이_잘못됨)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(4)
                .jsonPath("$.detail").isEqualTo(인증_필요)
                .consumeWith(결과 -> assertThat(new String(결과.getResponseBodyContent(), StandardCharsets.UTF_8))
                        .doesNotContainIgnoringCase("expired")
                        .doesNotContainIgnoringCase("jwt"));
    }

    @Test
    @DisplayName("directory.lookup 은 정확 조회 둘만 연다 — 다른 관리 GET 은 403. admin.read 도 정확 조회를 연다")
    void 조회_범위는_lookup_만_연다() {
        // given
        String 조회만 = bearer(TestTokens.발급한다("asking-app", Scopes.DIRECTORY_LOOKUP));
        String 관리_읽기 = bearer(TestTokens.발급한다("ops", Scopes.ADMIN_READ));

        // when, then
        client.get().uri("/admin/employees/lookup?userName=kim").header(HttpHeaders.AUTHORIZATION, 조회만).exchange()
                .expectStatus().isOk();
        client.get().uri("/admin/organizations/lookup?externalId=ORG-1").header(HttpHeaders.AUTHORIZATION, 조회만).exchange()
                .expectStatus().isOk();
        client.get().uri("/admin/employees").header(HttpHeaders.AUTHORIZATION, 조회만).exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 범위_부족);
        client.get().uri("/admin/employees/lookup?userName=kim").header(HttpHeaders.AUTHORIZATION, 관리_읽기).exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("프로브 셋(health·liveness·readiness)은 토큰 없이 열린다. 헬스의 다른 하위 경로(컴포넌트 세부)는 열리지 않는다")
    void 프로브는_토큰_없이_열린다() {
        // when, then
        for (String 프로브 : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            토큰_없이().get().uri(프로브).exchange()
                    .expectStatus().isOk();
        }
        토큰_없이().get().uri("/actuator/health/openFga").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("actuator 의 나머지는 actuator 범위가 필요하다 — 토큰 없으면 401, 범위가 없으면 403, 둘 다 본문 없이 WWW-Authenticate 만")
    void actuator_의_나머지는_actuator_범위가_필요하다() {
        // given
        String 관리_읽기 = bearer(TestTokens.발급한다("ops", Scopes.ADMIN_READ));
        String 지표 = bearer(TestTokens.발급한다("prometheus", Scopes.ACTUATOR));

        // when, then
        토큰_없이().get().uri("/actuator/prometheus").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 토큰_없음)
                .expectBody().isEmpty();
        client.get().uri("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, 관리_읽기).exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 범위_부족)
                .expectBody().isEmpty();
        client.get().uri("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, 지표).exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("규칙 밖의 경로는 처리기가 있어도 거절한다 — 토큰이 있으면 403, 없으면 401, 본문 없음. 로그아웃 끝점도 없다")
    void 그_밖의_경로는_거절한다() {
        // when, then
        client.get().uri("/elsewhere").exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 범위_부족)
                .expectBody().isEmpty();
        토큰_없이().get().uri("/elsewhere").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, 토큰_없음)
                .expectBody().isEmpty();
        토큰_없이().post().uri("/logout").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("토큰의 sub 가 요청 Context 의 행위자 client:<sub> 로 처리기까지 간다")
    void 행위자가_Context_로_간다() {
        // when, then — 기본 머리는 test-client 의 토큰이다
        client.get().uri("/admin/employees").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("client:test-client");
    }

    @Test
    @DisplayName("보안 체인과 디코더는 하나씩이다 — 부트의 기본 체인·키 목록 디코더는 물러나고, 시험 디코더와 이 모듈의 체인만 남는다")
    void 부트의_기본_체인과_디코더는_물러난다() {
        // when
        var 체인 = context.getBeansOfType(SecurityWebFilterChain.class);
        var 디코더 = context.getBeansOfType(ReactiveJwtDecoder.class);

        // then
        assertThat(체인).hasSize(1);
        assertThat(디코더).hasSize(1);
    }
}

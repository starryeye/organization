package dev.starryeye.organization.security;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.JwkSetUriReactiveJwtDecoderBuilderCustomizer;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

import static dev.starryeye.organization.security.Scopes.권한;

/**
 * 두 앱의 리소스 서버 보안 체인(설계 2026-10-28 §4, A2·A3·A5·A9). 토큰은 부트가 {@code spring.security.oauth2.resourceserver.jwt.*} 로 만드는 디코더
 * ({@code jwk-set-uri} 의 키로 서명, {@code issuer-uri} 의 발급자, 만료)로 그 자리에서 검증하고, 권한은 {@code scope} 클레임의 {@code SCOPE_<범위>} 다
 * (스프링 기본). 기동 때 인가 서버에 묻지 않는다 — 키 목록은 첫 토큰을 볼 때 받는다.
 *
 * <table>
 *   <caption>경로와 범위(§4.2) — 위에서부터 처음 맞는 규칙</caption>
 *   <tr><th>경로</th><th>요구</th></tr>
 *   <tr><td>{@code /scim/v2/**}(발견 포함)</td><td>{@code SCOPE_scim}</td></tr>
 *   <tr><td>{@code GET /admin/employees/lookup}·{@code /admin/organizations/lookup}</td><td>{@code SCOPE_directory.lookup} 또는 {@code SCOPE_admin.read}</td></tr>
 *   <tr><td>그 밖 {@code GET /admin/**}</td><td>{@code SCOPE_admin.read}</td></tr>
 *   <tr><td>{@code POST /admin/sync/**}</td><td>{@code SCOPE_admin.sync}({@code force=true}·{@code mode=wipe} 는 컨트롤러가 더 본다 — {@link Callers})</td></tr>
 *   <tr><td>{@code /actuator/health}·{@code /health/liveness}·{@code /health/readiness}</td><td>없음(프로브)</td></tr>
 *   <tr><td>그 밖 {@code /actuator/**}</td><td>{@code SCOPE_actuator}</td></tr>
 *   <tr><td>나머지</td><td>거절 — 인증이 있으면 403, 없으면 401</td></tr>
 * </table>
 *
 * <p>거절의 본문과 {@code WWW-Authenticate} 는 {@link AuthFailureResponses} 가 쓴다. 인증 필터 뒤의 {@link ActorWebFilter} 가 행위자를 요청 Context 에 싣는다.
 * bearer 토큰만 받는 API 라 CSRF·세션·요청 저장·로그아웃 끝점을 두지 않는다 — 거절한 요청도 서버에 아무것도 남기지 않는다.
 *
 * <p><b>자동 구성 순서.</b> 부트의 리소스 서버 자동 구성은 {@link SecurityWebFilterChain} 빈이 없을 때만 자기 체인({@code anyExchange().authenticated()})을
 * 만든다. 이 구성이 그보다 먼저 등록돼야 그것이 물러난다({@code before}). 액추에이터의 관리 보안 자동 구성은 부트의 리소스 서버 자동 구성 뒤라 함께 물러난다.
 * 두 앱은 {@code dev.starryeye.organization} 을 스캔하지만 {@code AutoConfiguration.imports} 에 적힌 이 클래스는 스캔에서 빠진다.
 */
@AutoConfiguration(before = ReactiveOAuth2ResourceServerAutoConfiguration.class)
@EnableWebFluxSecurity
@EnableConfigurationProperties({ResourceServerSettings.class, OAuth2ResourceServerProperties.class})
public class ResourceServerSecurityConfig {

    /** 키 목록 주소에 연결하는 한도 */
    static final Duration 키_목록_연결_한도 = Duration.ofSeconds(2);
    /** 키 목록 응답을 기다리는 한도 */
    static final Duration 키_목록_응답_한도 = Duration.ofSeconds(5);

    @Bean
    SecurityWebFilterChain 리소스_서버_체인(ServerHttpSecurity http, OAuth2ResourceServerProperties 리소스_서버,
                                      ResourceServerSettings 설정, ObjectProvider<ReactiveJwtDecoder> 디코더) {
        // 디코더를 꺼내기 전에 — 주소가 비면 부트가 디코더를 만들지 않아 "빈이 없다" 로 멈추기 전에 우리 문구로 멈춘다
        설정.검사한다(리소스_서버.getJwt().getIssuerUri(), 리소스_서버.getJwt().getJwkSetUri());

        return http
                .authorizeExchange(경로 -> 경로
                        .pathMatchers("/scim/v2/**").hasAuthority(권한(Scopes.SCIM))
                        .pathMatchers(HttpMethod.GET, "/admin/employees/lookup", "/admin/organizations/lookup")
                        .hasAnyAuthority(권한(Scopes.DIRECTORY_LOOKUP), 권한(Scopes.ADMIN_READ))
                        .pathMatchers(HttpMethod.GET, "/admin/**").hasAuthority(권한(Scopes.ADMIN_READ))
                        .pathMatchers(HttpMethod.POST, "/admin/sync/**").hasAuthority(권한(Scopes.ADMIN_SYNC))
                        .pathMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .pathMatchers("/actuator/**").hasAuthority(권한(Scopes.ACTUATOR))
                        .anyExchange().denyAll())
                .oauth2ResourceServer(리소스 -> 리소스
                        .jwt(jwt -> jwt.jwtDecoder(검증하지_못한_것을_알린다(디코더.getObject())))
                        .authenticationFailureHandler(AuthFailureResponses::토큰을_받지_않는다)
                        // 아래 둘은 exceptionHandling 의 것이 이긴다. 스프링 기본(error_description 을 싣는 Bearer 진입점·거절 처리기)이 어디에도 남지 않게 같은 것을 준다
                        .authenticationEntryPoint(AuthFailureResponses::토큰이_없다)
                        .accessDeniedHandler(AuthFailureResponses::범위가_모자란다))
                .exceptionHandling(예외 -> 예외
                        .authenticationEntryPoint(AuthFailureResponses::토큰이_없다)
                        .accessDeniedHandler(AuthFailureResponses::범위가_모자란다))
                .addFilterAfter(new ActorWebFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                // 쿠키로 인증하는 브라우저 세션이 없다 — 켜 두면 토큰 없는 POST 가 401 이 아니라 CSRF 403 이 된다
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // 스프링이 기본으로 여는 POST /logout(→ /login?logout 으로 보냄)을 두지 않는다
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(캐시 -> 캐시.requestCache(NoOpServerRequestCache.getInstance()))
                .build();
    }

    /**
     * 키 목록을 받는 클라이언트의 시간 한도 — 연결 {@link #키_목록_연결_한도}, 응답 {@link #키_목록_응답_한도}. 부트가 {@code jwk-set-uri} 로 만드는 디코더에
     * 붙는다(시험 디코더는 키 목록을 받지 않으므로 무관). 리액터 네티 기본값은 연결 30초·응답 무한이라, 키 목록 주소가 블랙홀이거나 멈춘 인가 서버면 토큰이 온 요청마다
     * 그만큼 붙잡힌다. 한도를 넘기면 키 목록을 받지 못한 것과 같이 503 이다.
     */
    @Bean
    JwkSetUriReactiveJwtDecoderBuilderCustomizer 키_목록_시간_한도() {
        HttpClient 연결 = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) 키_목록_연결_한도.toMillis())
                .responseTimeout(키_목록_응답_한도);
        WebClient 클라이언트 = WebClient.builder().clientConnector(new ReactorClientHttpConnector(연결)).build();
        return 빌더 -> 빌더.webClient(클라이언트);
    }

    /**
     * 디코더가 토큰 탓이 아닌 오류로 끝나면 {@link JwtException} 으로 바꾼다. 키 목록을 받지 못한 것이 대표다 — Nimbus 디코더는 이를
     * {@code IllegalStateException("Could not obtain the keys")} 로 내는데, 그대로 두면 인증 필터가 다루지 않는 예외라 500 이 된다. {@link JwtException}
     * (토큰이 잘못됐다는 {@code BadJwtException} 이 아닌 것)은 인증 관리자가 {@code AuthenticationServiceException} 으로 바꿔 실패 처리기
     * ({@link AuthFailureResponses#토큰을_받지_않는다})로 보낸다 — 503 과 WARN 한 줄. 토큰이 잘못된 것({@code BadJwtException})은 그대로 401 이다.
     * 바꾼 예외의 문구는 원래 오류의 종류와 문구다 — WARN 에 그대로 나간다(토큰은 싣지 않는다).
     */
    private static ReactiveJwtDecoder 검증하지_못한_것을_알린다(ReactiveJwtDecoder 디코더) {
        return 토큰 -> Mono.defer(() -> 디코더.decode(토큰))
                .onErrorMap(오류 -> !(오류 instanceof JwtException), 오류 -> new JwtException(오류.toString(), 오류));
    }
}

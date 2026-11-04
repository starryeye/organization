package dev.starryeye.authserver;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.AUTHORIZATION_ENDPOINT;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.GRANT_TYPES_SUPPORTED;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.ISSUER;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.JWKS_URI;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.RESPONSE_TYPES_SUPPORTED;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.TOKEN_ENDPOINT;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.TOKEN_ENDPOINT_AUTH_METHODS_SUPPORTED;

/**
 * 필터 체인 둘(설계 2026-10-28 §3.1). 이 빈들이 있으면 부트의 기본 인가 서버 보안 구성(폼 로그인·{@code /login} 으로 보내는 체인)은 물러난다.
 *
 * <ol>
 *   <li><b>인가 서버 체인</b> — 이 배포가 쓰는 끝점 셋만 받는다: 토큰, 서명 키 목록, 메타데이터. 토큰 요청은 클라이언트 인증(비밀)을 거치고, 범위 없는
 *       요청엔 등록 범위 전부를 준다({@link DefaultScopes}). 키 목록·메타데이터는 인증 없이 열린다. 메타데이터는 쓸 수 있는 것만 알린다.
 *   <li><b>나머지 체인</b> — 헬스({@code /actuator/health/**}, 프로브 포함)와 오류 디스패치만 열고 나머지는 모두 거절한다. Spring Authorization Server
 *       의 나머지 끝점 — 인가 코드({@code /oauth2/authorize}), 디바이스, 토큰 조회(introspection)·폐기(revocation) — 도 여기서 그 필터에 닿기 전에
 *       거절된다. 인가 코드 끝점은 아무 클라이언트 아이디(비밀이 아니다)로 익명 요청을 받으면 리다이렉트 URI 가 없는 클라이언트라 예외를 내고 요청마다 오류
 *       로그를 백 줄 넘게 찍었다. 조회·폐기는 발급 기록을 남기지 않아({@link NoRetentionAuthorizationService}) 살아 있는 토큰에 {@code active=false} 를,
 *       아무것도 못 하면서 200 을 답한다.
 * </ol>
 *
 * <p>둘 다 세션을 만들지 않는다 — 거절한 요청도 서버 메모리에 세션을 남기지 않고, 여러 대로 띄울 수 있다. 사용자 로그인이 없으니 폼 로그인도, 사용자 저장소도
 * 없다. 부트의 사용자 저장소 자동 구성({@code UserDetailsServiceAutoConfiguration})은 이 클래스패스에서 이미 물러나지만(§16 — 리소스 서버의
 * {@code OpaqueTokenIntrospector} 가 있고 {@code JwtDecoder} 빈이 있다) {@link AuthServerApplication} 이 명시적으로 뺀다.
 */
@Configuration(proxyBeanMethods = false)
class AuthServerSecurityConfig {

    /** 메타데이터 끝점(RFC 8414 §3 이 정한 경로 — 설정으로 바꾸지 않는다). */
    private static final String 메타데이터_경로 = "/.well-known/oauth-authorization-server";

    /**
     * 메타데이터에 남기는 항목. {@code authorization_endpoint}·{@code response_types_supported} 는 쓰지 않지만 Spring Authorization Server 의 메타데이터
     * 빌더가 반드시 요구해서(없으면 빌드가 실패한다) 남는다 — 그 끝점은 403 이다.
     */
    private static final Set<String> 남길_항목 = Set.of(ISSUER, TOKEN_ENDPOINT, JWKS_URI, AUTHORIZATION_ENDPOINT, RESPONSE_TYPES_SUPPORTED);

    @Bean
    @Order(1)
    SecurityFilterChain 인가_서버_체인(HttpSecurity http, OAuth2AuthorizationServerProperties 인가_서버_속성,
                                 AuthServerProperties 설정, AuthorizationServerSettings 끝점) throws Exception {
        // 체인을 만들기 전에 — Spring Authorization Server 가 발급자를 URL 로 검사하기 전에 우리 문구로 멈춘다
        ClientSettingsCheck.검사한다(인가_서버_속성, 설정);

        http.securityMatcher(끝점.getTokenEndpoint(), 끝점.getJwkSetEndpoint(), 메타데이터_경로)
                .with(OAuth2AuthorizationServerConfigurer.authorizationServer(), server -> server
                        .tokenEndpoint(token -> token.accessTokenRequestConverter(new DefaultScopes()))
                        .authorizationServerMetadataEndpoint(metadata -> metadata
                                .authorizationServerMetadataCustomizer(알림 -> 알림.claims(AuthServerSecurityConfig::쓸_수_있는_것만))))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // 나머지 체인과 같은 까닭으로 CSRF 를 끈다. 다만 disable 은 남지 않는다 — Spring Authorization Server 의 구성기가 빌드 때 csrf(…)
                // 를 다시 불러 CSRF 필터를 되살리고 자기 끝점(키 목록·메타데이터 GET, 토큰 POST)만 뺀다. 그러면 그 끝점에 다른 메서드로 온 익명
                // 요청(POST /oauth2/jwks 등)이 CSRF 검사에 걸려, 403 을 내기 전에 CSRF 토큰을 세션에 저장하려고 요청마다 세션을 만든다.
                // 그래서 모든 요청을 CSRF 검사에서 뺀다 — 검사하지 않는 요청은 토큰을 만들지도, 세션에 저장하지도 않는다
                .csrf(csrf -> csrf.ignoringRequestMatchers(AnyRequestMatcher.INSTANCE))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain 나머지_체인(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        // 다른 요청이 낸 오류(sendError·예외)를 /error 로 그리는 내부 디스패치. 막으면 모든 5xx 가 빈 403 이 되고, IdP 는 그것을 권한 문제로 읽는다.
                        // /error 를 직접 부르는 요청(REQUEST 디스패치)은 아래에서 거절된다
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().denyAll())
                // 쿠키로 인증하는 브라우저 세션이 없다 — 켜 두면 인증 없는 POST 마다 CSRF 토큰을 세션에 저장하려고 세션을 만든다
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    OAuth2AuthorizationService authorizationService() {
        return new NoRetentionAuthorizationService();
    }

    /** 메타데이터를 이 배포가 실제로 받는 것으로 줄인다 — client credentials 와 비밀 인증 방식(basic·post). */
    private static void 쓸_수_있는_것만(Map<String, Object> 항목) {
        항목.keySet().retainAll(남길_항목);
        항목.put(GRANT_TYPES_SUPPORTED, List.of(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()));
        항목.put(TOKEN_ENDPOINT_AUTH_METHODS_SUPPORTED, List.of(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(), ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue()));
    }
}

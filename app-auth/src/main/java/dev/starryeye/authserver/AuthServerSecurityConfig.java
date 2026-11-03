package dev.starryeye.authserver;

import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 필터 체인 둘(설계 2026-10-28 §3.1). 이 빈들이 있으면 부트의 기본 인가 서버 보안 구성(폼 로그인·{@code /login} 으로 보내는 체인)은 물러난다.
 *
 * <ol>
 *   <li><b>인가 서버 체인</b> — 인가 서버 끝점만 받는다. 토큰 요청은 클라이언트 인증(비밀)을 거치고, 범위 없는 요청엔 등록 범위 전부를 준다
 *       ({@link DefaultScopes}). 키 목록·메타데이터는 인증 없이 열린다. 토큰 조회(introspection)·폐기(revocation)는 닫는다 — 발급 기록을 남기지 않아
 *       ({@link NoRetentionAuthorizationService}) 조회는 살아 있는 토큰에 {@code active=false} 를, 폐기는 아무것도 못 하면서 200 을 답하기 때문이다.
 *   <li><b>나머지 체인</b> — 헬스({@code /actuator/health/**}, 프로브 포함)만 인증 없이 열고 나머지는 모두 거절한다.
 * </ol>
 *
 * <p>둘 다 세션을 만들지 않는다 — 거절한 요청도 서버 메모리에 세션을 남기지 않고, 여러 대로 띄울 수 있다. 사용자 로그인이 없으니 폼 로그인도, 사용자 저장소도
 * 없다. 부트의 사용자 저장소 자동 구성({@code UserDetailsServiceAutoConfiguration})은 이 클래스패스에서 이미 물러나지만(§16 — 리소스 서버의
 * {@code OpaqueTokenIntrospector} 가 있고 {@code JwtDecoder} 빈이 있다) {@link AuthServerApplication} 이 명시적으로 뺀다.
 */
@Configuration(proxyBeanMethods = false)
class AuthServerSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain 인가_서버_체인(HttpSecurity http, OAuth2AuthorizationServerProperties 인가_서버_속성,
                                 AuthServerProperties 설정, AuthorizationServerSettings 끝점) throws Exception {
        // 체인을 만들기 전에 — Spring Authorization Server 가 발급자를 URL 로 검사하기 전에 우리 문구로 멈춘다
        ClientSettingsCheck.검사한다(인가_서버_속성, 설정);

        var 인가_서버 = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(인가_서버.getEndpointsMatcher())
                .with(인가_서버, server -> server
                        .tokenEndpoint(token -> token.accessTokenRequestConverter(new DefaultScopes())))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(끝점.getTokenIntrospectionEndpoint(), 끝점.getTokenRevocationEndpoint()).denyAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain 나머지_체인(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
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
}

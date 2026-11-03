package dev.starryeye.authserver;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 인가 서버(설계 2026-10-28 §3, A1). client credentials 로 JWT 액세스 토큰을 발급한다 — 토큰({@code /oauth2/token}), 서명 키 목록
 * ({@code /oauth2/jwks}), 메타데이터({@code /.well-known/oauth-authorization-server}).
 *
 * <p><b>설정 파일 이름은 {@code auth-server} 다</b>({@code auth-server.yml}, {@code auth-server-prod.yml}). 이 앱은 진짜 인가 서버로 하는 시험(§8.2)에서
 * 다른 앱의 시험 클래스패스에 올라가는데, {@code application.yml} 이면 그 앱의 것과 이름이 겹친다. 그래서 {@link #애플리케이션()} 이
 * {@code spring.config.name=auth-server} 를 기본값으로 둔다. 패키지도 다른 앱의 스캔 범위({@code dev.starryeye.organization}) 밖에 둔다.
 */
// 사용자 로그인이 없다 — 부트가 사용자 저장소를 만들고 "Using generated security password" 를 로그에 찍지 않게 명시적으로 뺀다(AuthServerSecurityConfig)
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(AuthServerProperties.class)
public class AuthServerApplication {

    public static void main(String[] args) {
        애플리케이션().run(args);
    }

    /**
     * {@code spring.config.name=auth-server} 를 기본값으로 둔 빌더. 기본값이라 명령줄 {@code --spring.config.name} 이 이긴다. 같은 JVM 에서 이 앱을 띄우는
     * 시험도 이것을 쓴다.
     */
    public static SpringApplicationBuilder 애플리케이션() {
        return new SpringApplicationBuilder(AuthServerApplication.class)
                .properties("spring.config.name=auth-server");
    }
}

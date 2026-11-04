package dev.starryeye.organization.security;

import dev.starryeye.organization.core.audit.Actor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 보안 시험용 작은 앱 — 두 앱의 표면(SCIM·관리 API·actuator)을 흉내 내는 라우트만 올리고 자동 구성은 앱과 같게 받는다(이 모듈과 testFixtures 의
 * {@code AutoConfiguration.imports} 포함). 컴포넌트 스캔은 하지 않는다.
 *
 * <p>라우트는 보안을 지나면 200(쓰기는 202)과 짧은 글을 돌려준다. {@code GET /admin/employees} 만 요청 Context 의 행위자({@link Actor#from})를 돌려준다.
 * {@code GET /elsewhere} 는 규칙 밖의 경로다 — 처리기가 있어도 보안이 거절해야 한다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class MockRoutesApplication {

    @Bean
    RouterFunction<ServerResponse> 흉내_라우트() {
        return RouterFunctions.route()
                .GET("/scim/v2/Users", 요청 -> 답한다("scim"))
                .GET("/admin/employees", 요청 -> Mono.deferContextual(context -> ServerResponse.ok().bodyValue(Actor.from(context))))
                .GET("/admin/employees/lookup", 요청 -> 답한다("employee lookup"))
                .GET("/admin/organizations/lookup", 요청 -> 답한다("organization lookup"))
                .POST("/admin/sync/full", 요청 -> ServerResponse.accepted().bodyValue("started"))
                .GET("/actuator/health", 요청 -> 답한다("UP"))
                .GET("/actuator/health/liveness", 요청 -> 답한다("UP"))
                .GET("/actuator/health/readiness", 요청 -> 답한다("UP"))
                .GET("/actuator/health/openFga", 요청 -> 답한다("UP"))
                .GET("/actuator/prometheus", 요청 -> 답한다("metrics"))
                .GET("/elsewhere", 요청 -> 답한다("elsewhere"))
                .build();
    }

    private static Mono<ServerResponse> 답한다(String 글) {
        return ServerResponse.ok().bodyValue(글);
    }
}

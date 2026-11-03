package dev.starryeye.organization.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 보안 필터가 앞에서 거절할 때의 401·403(설계 2026-10-28 §4.4, A5). 이 거절은 {@code ScimRouter} 의 오류 번역도 관리 API 의 {@code AdminExceptionHandler} 도
 * 거치지 않으므로 여기서 그 표면의 오류 형식으로 직접 쓴다 — connector-scim·admin-api 에 기대지 않고 같은 모양으로.
 *
 * <table>
 *   <caption>표면별 본문</caption>
 *   <tr><th>경로</th><th>본문</th></tr>
 *   <tr><td>{@code /scim/v2/**}</td><td>{@code application/scim+json} SCIM Error — {@code schemas}·{@code status}(문자열)·{@code detail}</td></tr>
 *   <tr><td>{@code /admin/**}</td><td>{@code application/problem+json} — {@code type}(about:blank)·{@code title}(상태 문구)·{@code status}·{@code detail}</td></tr>
 *   <tr><td>그 밖(actuator 등)</td><td>없음</td></tr>
 * </table>
 *
 * <p>모두 {@code WWW-Authenticate} 를 단다(RFC 6750 §3·§3.1). 어느 것을 다는지는 어디서 거절했는지로 정한다 — 예외 종류를 늘어놓지 않는다.
 * <ul>
 *   <li>{@link #토큰이_없다} — 인증 없이 인증이 필요한 곳에 왔다. 보낸 자격이 없으니 {@code error} 를 싣지 않는다: {@code Bearer}.</li>
 *   <li>{@link #토큰을_받지_않는다} — 토큰이 왔는데 받아들이지 못했다(서명·발급자·만료·꼴, 그리고 검증 자체를 하지 못한 것): {@code Bearer error="invalid_token"}.</li>
 *   <li>{@link #범위가_모자란다} — 인증은 됐는데 그 경로의 범위가 없다: {@code Bearer error="insufficient_scope"}.</li>
 * </ul>
 * 스프링 기본(Bearer 진입점·거절 처리기)은 {@code error_description} 에 검증 실패의 세부(만료 시각 등)를 싣는다. 여기서는 토큰 내용도 실패 세부도 머리·본문 어디에도
 * 싣지 않는다.
 */
@Slf4j
final class AuthFailureResponses {

    static final String 토큰_없음 = "Bearer";
    static final String 토큰이_잘못됨 = "Bearer error=\"invalid_token\"";
    static final String 범위_부족 = "Bearer error=\"insufficient_scope\"";

    static final String 인증_필요_문구 = "인증이 필요하다";
    static final String 범위_없음_문구 = "이 요청에 필요한 범위가 없다";

    /** RFC 7644 §3.1 — {@code ScimRouter.SCIM_JSON} 과 같다 */
    private static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");
    /** RFC 7644 §3.12 — {@code ScimSchemas.ERROR} 와 같다 */
    private static final String SCIM_ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";

    /** {@code /scim/v2} 자신도 맞는다 */
    private static final PathPattern SCIM = PathPatternParser.defaultInstance.parse("/scim/v2/**");
    private static final PathPattern 관리_API = PathPatternParser.defaultInstance.parse("/admin/**");

    /** 정한 문구만 쓰므로 앱의 매퍼 설정과 무관한 것으로 충분하다 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthFailureResponses() {
    }

    /** 진입점 — 토큰 없이 인증이 필요한 곳에 왔다. 401, {@code Bearer}. */
    static Mono<Void> 토큰이_없다(ServerWebExchange exchange, AuthenticationException 실패) {
        return 거절한다(exchange, HttpStatus.UNAUTHORIZED, 토큰_없음, 인증_필요_문구);
    }

    /**
     * 인증 실패 처리기 — 토큰이 왔는데 받아들이지 못했다. 401, {@code invalid_token}.
     *
     * <p>{@link AuthenticationServiceException} 은 토큰 탓이 아니라 검증을 하지 못한 것이다 — 인가 서버의 키 목록에 닿지 않는 것이 대표다
     * ({@link ResourceServerSecurityConfig} 가 디코더의 그런 오류를 이리로 모은다). 스프링 기본은 이를 다시 던져 500 이 되지만, 이 배포는 기동도 요청도 인가
     * 서버에 묶이지 않게 401 로 답하고(클라이언트는 다시 받는다) 원인을 WARN 한 줄로 남긴다 — 스택은 남기지 않는다. 그러지 않으면 운영자는 이유 모를 401 만 본다.
     */
    static Mono<Void> 토큰을_받지_않는다(WebFilterExchange 교환, AuthenticationException 실패) {
        if (실패 instanceof AuthenticationServiceException) {
            log.warn("토큰을 검증하지 못해 401 로 답한다 — 인가 서버의 키 목록(jwk-set-uri)에 닿는지 본다: {} ({})",
                    실패.getMessage(), NestedExceptionUtils.getMostSpecificCause(실패).toString());
        }
        return 거절한다(교환.getExchange(), HttpStatus.UNAUTHORIZED, 토큰이_잘못됨, 인증_필요_문구);
    }

    /** 거절 처리기 — 인증은 됐는데 그 경로의 범위가 없다(규칙 밖의 경로 포함). 403, {@code insufficient_scope}. */
    static Mono<Void> 범위가_모자란다(ServerWebExchange exchange, AccessDeniedException 거절) {
        return 거절한다(exchange, HttpStatus.FORBIDDEN, 범위_부족, 범위_없음_문구);
    }

    private static Mono<Void> 거절한다(ServerWebExchange exchange, HttpStatus 상태, String 도전, String 문구) {
        ServerHttpResponse 응답 = exchange.getResponse();
        응답.setStatusCode(상태);
        응답.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, 도전);
        PathContainer 경로 = exchange.getRequest().getPath().pathWithinApplication();
        if (SCIM.matches(경로)) {
            Map<String, Object> 본문 = new LinkedHashMap<>();
            본문.put("schemas", List.of(SCIM_ERROR));
            본문.put("status", String.valueOf(상태.value()));
            본문.put("detail", 문구);
            return 쓴다(응답, SCIM_JSON, 본문);
        }
        if (관리_API.matches(경로)) {
            Map<String, Object> 본문 = new LinkedHashMap<>();
            본문.put("type", "about:blank");
            본문.put("title", 상태.getReasonPhrase());
            본문.put("status", 상태.value());
            본문.put("detail", 문구);
            return 쓴다(응답, MediaType.APPLICATION_PROBLEM_JSON, 본문);
        }
        return 응답.setComplete();
    }

    private static Mono<Void> 쓴다(ServerHttpResponse 응답, MediaType 종류, Map<String, Object> 본문) {
        byte[] 바이트;
        try {
            바이트 = JSON.writeValueAsBytes(본문);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        응답.getHeaders().setContentType(종류);
        return 응답.writeWith(Mono.just(응답.bufferFactory().wrap(바이트)));
    }
}

package dev.starryeye.organization.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
 * 보안 필터가 앞에서 끝낼 때의 401·403·503(설계 2026-10-28 §4.4, A5). 이 응답은 {@code ScimRouter} 의 오류 번역도 관리 API 의 {@code AdminExceptionHandler}
 * 도 거치지 않으므로 여기서 그 표면의 오류 형식으로 직접 쓴다 — connector-scim·admin-api 에 기대지 않고 같은 모양으로.
 *
 * <table>
 *   <caption>표면별 본문</caption>
 *   <tr><th>경로</th><th>본문</th></tr>
 *   <tr><td>{@code /scim/v2/**}</td><td>{@code application/scim+json} SCIM Error — {@code schemas}·{@code status}(문자열)·{@code detail}</td></tr>
 *   <tr><td>{@code /admin/**}</td><td>{@code application/problem+json} — {@code type}(about:blank)·{@code title}(상태 문구)·{@code status}·{@code detail}</td></tr>
 *   <tr><td>그 밖(actuator 등)</td><td>없음</td></tr>
 * </table>
 *
 * <p>어떤 응답인지는 어디서 끝났는지로 정한다 — 예외 종류를 늘어놓지 않는다.
 * <ul>
 *   <li>{@link #토큰이_없다} — 인증 없이 인증이 필요한 곳에 왔다. 401, 보낸 자격이 없으니 {@code error} 없이 {@code WWW-Authenticate: Bearer}.</li>
 *   <li>{@link #토큰을_받지_않는다} — 토큰이 왔는데 받아들이지 못했다(서명·발급자·만료·꼴 등 무엇이든). 401, {@code Bearer error="invalid_token"}. 단
 *       원인이 키 목록을 받지 못한 것({@link KeySetUnavailableException})이면 503 + {@code Retry-After}, {@code WWW-Authenticate} 없음.</li>
 *   <li>{@link #범위가_모자란다} — 인증은 됐는데 그 경로의 범위가 없다. 403, {@code Bearer error="insufficient_scope"}.</li>
 * </ul>
 * 스프링 기본(Bearer 진입점·거절 처리기)은 {@code error_description} 에 검증 실패의 세부(만료 시각 등)를 싣는다. 여기서는 토큰 내용도 실패 세부도 머리·본문 어디에도
 * 싣지 않는다(RFC 6750 §3·§3.1).
 */
@Slf4j
final class AuthFailureResponses {

    static final String 토큰_없음 = "Bearer";
    static final String 토큰이_잘못됨 = "Bearer error=\"invalid_token\"";
    static final String 범위_부족 = "Bearer error=\"insufficient_scope\"";

    static final String 인증_필요_문구 = "인증이 필요하다";
    static final String 범위_없음_문구 = "이 요청에 필요한 범위가 없다";
    /** 503 의 detail — 관리 API 처리기({@code AdminExceptionHandler})의 일시 장애 문구와 같다 */
    static final String 일시_장애_문구 = "일시 장애 — 잠시 뒤 다시 시도한다";
    /** 503 의 {@code Retry-After}(초). 키 목록이 돌아오길 기다리며 클라이언트가 스스로 다시 부른다 */
    static final long 재시도_초 = 10;

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
        return 답한다(exchange, HttpStatus.UNAUTHORIZED, 토큰_없음, 인증_필요_문구);
    }

    /**
     * 인증 실패 처리기 — 토큰이 왔는데 받아들이지 못했다. 401, {@code invalid_token}.
     *
     * <p>단 원인 사슬에 {@link KeySetUnavailableException} 이 있으면 토큰 탓이 아니라 인가 서버의 키 목록을 받지 못한 것이다 — 그 표지는 키 목록을 받는 자리
     * ({@link KeySetClient})에서만 만든다. 스프링 기본은 이를 다시 던져 500 이 되고, 401 {@code invalid_token} 은 클라이언트에게 멀쩡한 토큰을 버리라는 뜻이라
     * client credentials 클라이언트는 인가 서버를 거듭 두드리고 IdP 는 거듭된 401 을 자격 문제로 읽어 프로비저닝을 격리할 수 있다. 그래서 일시 장애로 503 +
     * {@code Retry-After}({@link #재시도_초}) 를 낸다 — 클라이언트가 스스로 다시 부르고, 관리 API 의 일시 장애 규칙(503, 운영 준비 D8)과 같다.
     * {@code WWW-Authenticate} 는 달지 않는다. 원인은 WARN 한 줄로 남긴다 — 스택도 토큰도 남기지 않는다.
     *
     * <p>예외의 종류({@link AuthenticationServiceException} 인가)로 가르지 않는다. 스프링은 토큰을 읽다 난 예상 밖의 실패도 그 종류로 내므로, 그러면 아무나
     * 보낼 수 있는 토큰이 503 과 "장애" 경보를 꾸며 낸다.
     */
    static Mono<Void> 토큰을_받지_않는다(WebFilterExchange 교환, AuthenticationException 실패) {
        var 키_목록_장애 = KeySetUnavailableException.원인에서_찾는다(실패);
        if (키_목록_장애.isPresent()) {
            log.warn("키 목록을 받지 못해 503 으로 답한다: {}", 키_목록_장애.get().getMessage());
            교환.getExchange().getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(재시도_초));
            return 답한다(교환.getExchange(), HttpStatus.SERVICE_UNAVAILABLE, null, 일시_장애_문구);
        }
        return 답한다(교환.getExchange(), HttpStatus.UNAUTHORIZED, 토큰이_잘못됨, 인증_필요_문구);
    }

    /** 거절 처리기 — 인증은 됐는데 그 경로의 범위가 없다(규칙 밖의 경로 포함). 403, {@code insufficient_scope}. */
    static Mono<Void> 범위가_모자란다(ServerWebExchange exchange, AccessDeniedException 거절) {
        return 답한다(exchange, HttpStatus.FORBIDDEN, 범위_부족, 범위_없음_문구);
    }

    /** 상태와 그 표면의 본문을 쓴다. {@code 도전} 이 있으면 {@code WWW-Authenticate} 로 단다. */
    private static Mono<Void> 답한다(ServerWebExchange exchange, HttpStatus 상태, String 도전, String 문구) {
        ServerHttpResponse 응답 = exchange.getResponse();
        응답.setStatusCode(상태);
        if (도전 != null) {
            응답.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, 도전);
        }
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

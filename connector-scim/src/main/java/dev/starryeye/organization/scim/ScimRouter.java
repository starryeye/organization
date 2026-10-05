package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.usecase.DirectoryConflictException;
import dev.starryeye.organization.core.usecase.GroupGraphTooLargeException;
import dev.starryeye.organization.scim.dto.ScimError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SCIM 2.0 라우팅. 에러 번역은 여기 한 곳에서만 한다 —
 * 핸들러마다 try/catch 를 두면 응답 형식이 어긋난다.
 */
@Slf4j
public final class ScimRouter {

    /** RFC 7644 §3.1 이 규정한 SCIM 응답 미디어 타입. 핸들러의 모든 응답이 이걸 써야 한다. */
    public static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");

    /** 본문 한도 기본값 — Spring Boot 의 {@code spring.codec.max-in-memory-size} 기본(256KB)과 같다 */
    private static final long 기본_본문_한도 = 256 * 1024;

    /** 지원하지 않는 SCIM 엔드포인트 — RFC 7644 는 501 로 알린다(§3.12, /Me 는 §3.11). Bulk 는 ServiceProviderConfig 가 지원 안 함으로 선언했다. */
    private static final List<PathPattern> 지원_안_함 = 패턴("/scim/v2/Bulk", "/scim/v2/Me", "/scim/v2/Me/**",
            "/scim/v2/Schemas", "/scim/v2/Schemas/**", "/scim/v2/ResourceTypes", "/scim/v2/ResourceTypes/**");

    /** 있는 경로와 받는 메서드 — {@link #scimRoutes} 의 라우트와 같아야 한다. 틀린 메서드는 405 + Allow 로 알린다. */
    private static final Map<PathPattern, Set<HttpMethod>> 받는_메서드 = Map.ofEntries(
            Map.entry(패턴하나("/scim/v2/Users"), Set.of(HttpMethod.GET, HttpMethod.POST)),
            Map.entry(패턴하나("/scim/v2/Users/.search"), Set.of(HttpMethod.POST)),
            Map.entry(패턴하나("/scim/v2/Users/{id}"), Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)),
            Map.entry(패턴하나("/scim/v2/Groups"), Set.of(HttpMethod.GET, HttpMethod.POST)),
            Map.entry(패턴하나("/scim/v2/Groups/.search"), Set.of(HttpMethod.POST)),
            Map.entry(패턴하나("/scim/v2/Groups/{id}"), Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)),
            Map.entry(패턴하나("/scim/v2/ServiceProviderConfig"), Set.of(HttpMethod.GET)),
            Map.entry(패턴하나("/scim/v2"), Set.of(HttpMethod.GET)),
            Map.entry(패턴하나("/scim/v2/"), Set.of(HttpMethod.GET)),
            Map.entry(패턴하나("/scim/v2/.search"), Set.of(HttpMethod.POST)));

    private ScimRouter() {
    }

    public static RouterFunction<ServerResponse> scimRoutes(ScimUserHandler users,
                                                            ScimGroupHandler groups,
                                                            ScimListHandler lists) {
        return scimRoutes(users, groups, lists, TemporaryFailureClassifier.표지만(), 기본_본문_한도);
    }

    /**
     * @param 분류기   일시 장애(503)와 버그(500)를 가르는 규칙
     * @param 본문_한도 코덱이 본문을 메모리에 받는 한도(바이트) — 413 응답 문구에 싣는다
     */
    public static RouterFunction<ServerResponse> scimRoutes(ScimUserHandler users,
                                                            ScimGroupHandler groups,
                                                            ScimListHandler lists,
                                                            TemporaryFailureClassifier 분류기,
                                                            long 본문_한도) {
        return RouterFunctions.route()
                .GET("/scim/v2/Users", lists::listUsers)
                .POST("/scim/v2/Users/.search", lists::searchUsers)
                .POST("/scim/v2/Users", users::create)
                .GET("/scim/v2/Users/{id}", users::get)
                .PUT("/scim/v2/Users/{id}", users::replace)
                .PATCH("/scim/v2/Users/{id}", users::patch)
                .DELETE("/scim/v2/Users/{id}", users::delete)
                .GET("/scim/v2/Groups", lists::listGroups)
                .POST("/scim/v2/Groups/.search", lists::searchGroups)
                .POST("/scim/v2/Groups", groups::create)
                .GET("/scim/v2/Groups/{id}", groups::get)
                .PUT("/scim/v2/Groups/{id}", groups::replace)
                .PATCH("/scim/v2/Groups/{id}", groups::patch)
                .DELETE("/scim/v2/Groups/{id}", groups::delete)
                .GET("/scim/v2/ServiceProviderConfig", request -> serviceProviderConfig())
                // 서버 루트 조회(여러 리소스 종류를 한꺼번에)는 지원하지 않는다 — S-1 설계 §4.6
                .GET("/scim/v2", request -> rootQuery())
                .GET("/scim/v2/", request -> rootQuery())
                .POST("/scim/v2/.search", request -> rootQuery())
                // 위 어느 라우트에도 맞지 않은 /scim/v2 아래 요청 — 반드시 마지막이다. 스프링의 기본 404 는 SCIM Error 가 아니라 IdP 가 읽지 못한다(점검 S7)
                .route(RequestPredicates.path("/scim/v2/**"), ScimRouter::라우트_밖)
                .onError(Throwable.class, (error, request) -> toScimError(error, 분류기, 본문_한도))
                .build();
    }

    private static Mono<ServerResponse> rootQuery() {
        return Mono.error(ScimException.notImplemented(
                "서버 루트 조회는 지원하지 않습니다 — /scim/v2/Users 나 /scim/v2/Groups 로 조회하세요"));
    }

    private static PathPattern 패턴하나(String 경로) {
        return PathPatternParser.defaultInstance.parse(경로);
    }

    private static List<PathPattern> 패턴(String... 경로) {
        return Stream.of(경로).map(ScimRouter::패턴하나).toList();
    }

    private static Mono<ServerResponse> 라우트_밖(ServerRequest request) {
        PathContainer path = request.requestPath().pathWithinApplication();
        if (지원_안_함.stream().anyMatch(p -> p.matches(path))) {
            return Mono.error(ScimException.notImplemented("지원하지 않는 SCIM 엔드포인트입니다: " + path.value()));
        }
        // `.search` 가 `{id}` 에도 맞으므로 받는 메서드를 모두 모은다. 헤더 순서가 실행마다 달라지지 않게 이름순으로 둔다
        Set<HttpMethod> 받는것 = 받는_메서드.entrySet().stream()
                .filter(e -> e.getKey().matches(path))
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(HttpMethod::name))));
        if (!받는것.isEmpty()) {
            return Mono.error(ScimException.methodNotAllowed(
                    "이 경로는 " + request.method() + " 를 받지 않습니다: " + path.value(), 받는것));
        }
        return Mono.error(ScimException.notFound("없는 SCIM 경로입니다: " + path.value()));
    }

    static Mono<ServerResponse> toScimError(Throwable error, TemporaryFailureClassifier 분류기, long 본문_한도) {
        if (error instanceof ScimException scim) {
            return write(scim.getStatus(), scim.getScimType(), scim.getMessage(),
                    scim.getRetryAfter().orElse(null), scim.getAllow().orElse(null));
        }
        // userName 이 이미 있거나 조직 externalId 가 겹친다 — RFC 7644 §3.12 의 409 uniqueness. 판단은 락 안에서 했다(SCIM 쓰기 락 설계 §3·§4).
        if (error instanceof DirectoryConflictException conflict) {
            return write(HttpStatus.CONFLICT, "uniqueness", conflict.getMessage(), null);
        }
        // 조직 계층이 순환 검사 한도를 넘었다 — 같은 요청은 다시 보내도 늘 넘으므로 영구 거절(설계 2026-10-03 §4.2, 점검 P2). 500 이면 IdP 가 같은 실패를 되풀이한다.
        if (error instanceof GroupGraphTooLargeException tooLarge) {
            return write(HttpStatus.BAD_REQUEST, "invalidValue", tooLarge.getMessage(), null);
        }
        // 본문이 코덱의 버퍼 한도를 넘었다. DecodingException 이 감싸 오므로 그 분기보다 먼저 본다 — 400 이면 IdP 가 같은 본문을 되풀이한다(점검 M3)
        if (원인에_있다(error, DataBufferLimitException.class)) {
            return write(HttpStatus.PAYLOAD_TOO_LARGE, null,
                    "요청 본문이 한도(%d바이트)를 넘었습니다 — 큰 조직의 멤버는 PATCH 로 나눠 보내세요".formatted(본문_한도), null);
        }
        // 다시 보내면 나을 수 있는 실패(락을 얻지 못함, 쓰기 차단, 하위 시스템 일시 장애)는 503 + Retry-After 다. IdP 는 503 을 재시도 신호로 보므로
        // 프로비저닝이 유실되지 않고, 재시도 시점에는 풀린 상태 위에서 처리된다. 400 이나 500 으로 뭉개면 IdP 가 영구 실패로 판단해 포기하거나
        // 무한히 재시도한다. 500 은 버그에만 남긴다(점검 M4, 설계 2026-10-05 §3.4).
        // 문구는 우리 것만 쓴다 — 예외 메시지에는 OpenFGA 서버 메시지·라이브러리 예외 문자열·락 쥔 쪽의 용도가 들어 있고, 이 엔드포인트는 인증이 없다(점검 S10). 메시지는 로그로만 간다.
        Optional<Duration> 대기 = 분류기.재시도_대기(error);
        if (대기.isPresent()) {
            log.warn("SCIM 요청이 일시 장애로 실패했다 — 503, Retry-After {}초", 재시도_초(대기.get()), error);
            return write(HttpStatus.SERVICE_UNAVAILABLE, null,
                    "일시적으로 처리할 수 없습니다 — %d초 뒤 다시 보내 주세요".formatted(재시도_초(대기.get())), 대기.get());
        }
        // 본문 파싱 실패 등 SCIM 이 모르는 예외는 400 으로 번역한다.
        if (error instanceof DecodingException || error instanceof ServerWebInputException) {
            return write(HttpStatus.BAD_REQUEST, "invalidSyntax", "요청 본문을 해석할 수 없습니다", null);
        }
        // Content-Type 불일치(415) 등 WebFlux 가 이미 적절한 상태코드를 정해준 예외는 그 상태를 쓴다. 여기를 500 으로 뭉개면 IdP 가 결코 성공할 수 없는
        // 요청을 무한히 재시도하게 된다. Spring 의 reason 은 싣지 않는다 — 내부 클래스 이름이 나간다(점검 S10).
        if (error instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.valueOf(rse.getStatusCode().value());
            return write(status, null, 상태_문구(status), null);
        }
        log.error("SCIM 요청 처리 중 예기치 않은 오류", error);
        return write(HttpStatus.INTERNAL_SERVER_ERROR, null, "내부 오류가 발생했습니다", null);
    }

    /** 원인 사슬 어디에든 이 종류가 있는가 — 같은 예외를 두 번 보지 않는다 */
    private static boolean 원인에_있다(Throwable error, Class<? extends Throwable> 종류) {
        Set<Throwable> 본것 = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable 원인 = error; 원인 != null && 본것.add(원인); 원인 = 원인.getCause()) {
            if (종류.isInstance(원인)) {
                return true;
            }
        }
        return false;
    }

    /** Retry-After 와 문구에 쓰는 초 — 정수 초(RFC 9110 §10.2.3)이고, 1초보다 짧은 대기도 0 이 아니라 1 로 올린다 */
    static long 재시도_초(Duration 대기) {
        long 올림 = 대기.toSeconds() + (대기.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, 올림);
    }

    private static String 상태_문구(HttpStatus status) {
        if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return "Content-Type 은 application/scim+json 또는 application/json 이어야 합니다";
        }
        return status.getReasonPhrase();
    }

    private static Mono<ServerResponse> write(HttpStatus status, String scimType, String detail, Duration retryAfter) {
        return write(status, scimType, detail, retryAfter, null);
    }

    private static Mono<ServerResponse> write(HttpStatus status, String scimType, String detail,
                                              Duration retryAfter, Set<HttpMethod> allow) {
        ScimError body = new ScimError(List.of(ScimSchemas.ERROR),
                String.valueOf(status.value()), scimType, detail);
        ServerResponse.BodyBuilder response = ServerResponse.status(status).contentType(SCIM_JSON);
        if (retryAfter != null) {
            response = response.header(HttpHeaders.RETRY_AFTER, String.valueOf(재시도_초(retryAfter)));
        }
        if (allow != null) {
            response = response.allow(allow);
        }
        return response.bodyValue(body);
    }

    /**
     * 지원하는 기능을 정직하게 선언한다. 필터는 {@code eq}·{@code and} 만 받고 나머지는 {@code invalidFilter}
     * 다 — RFC 7644 는 필터 지원 여부만 선언하게 하고 부분 지원을 400 으로 알리게 한다(S-1 설계 §4.1).
     * 정렬은 인덱스 키({@code userName}/{@code displayName})로만 한다.
     */
    private static Mono<ServerResponse> serviceProviderConfig() {
        Map<String, Object> config = Map.of(
                "schemas", List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG),
                "patch", Map.of("supported", true),
                "bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0),
                "filter", Map.of("supported", true, "maxResults", ScimQuery.MAX_COUNT),
                "changePassword", Map.of("supported", false),
                "sort", Map.of("supported", true),
                "etag", Map.of("supported", false),
                "authenticationSchemes", List.of());
        return ServerResponse.ok().contentType(SCIM_JSON).bodyValue(config);
    }
}

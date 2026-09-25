package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.scim.dto.ScimError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * SCIM 2.0 라우팅. 에러 번역은 여기 한 곳에서만 한다 —
 * 핸들러마다 try/catch 를 두면 응답 형식이 어긋난다.
 */
@Slf4j
public final class ScimRouter {

    /** RFC 7644 §3.1 이 규정한 SCIM 응답 미디어 타입. 핸들러의 모든 응답이 이걸 써야 한다. */
    public static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");

    private ScimRouter() {
    }

    public static RouterFunction<ServerResponse> scimRoutes(ScimUserHandler users,
                                                            ScimGroupHandler groups,
                                                            ScimListHandler lists) {
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
                .onError(Throwable.class, ScimRouter::toScimError)
                .build();
    }

    private static Mono<ServerResponse> rootQuery() {
        return Mono.error(ScimException.notImplemented(
                "서버 루트 조회는 지원하지 않습니다 — /scim/v2/Users 나 /scim/v2/Groups 로 조회하세요"));
    }

    private static Mono<ServerResponse> toScimError(Throwable error, ServerRequest request) {
        if (error instanceof ScimException scim) {
            return write(scim.getStatus(), scim.getScimType(), scim.getMessage());
        }
        // 변경 락을 얻지 못했거나(다른 인스턴스가 쥐고 있음, 재적재가 도는 중 포함) 쓰기 직전에
        // 리스를 잃은 경우는 503 이다. IdP 는 503 을 재시도 신호로 보므로 프로비저닝이 유실되지
        // 않고, 재시도 시점에는 락이 풀린 깨끗한 상태 위에서 처리된다. 400 이나 500 으로 뭉개면
        // IdP 가 영구 실패로 판단해 포기하거나 무한히 재시도한다.
        if (error instanceof LockUnavailableException lockUnavailable) {
            return write(HttpStatus.SERVICE_UNAVAILABLE, null, lockUnavailable.getMessage());
        }
        // 본문 파싱 실패 등 SCIM 이 모르는 예외는 400 으로 번역한다.
        if (error instanceof DecodingException || error instanceof ServerWebInputException) {
            return write(HttpStatus.BAD_REQUEST, "invalidSyntax", "요청 본문을 해석할 수 없습니다");
        }
        // Content-Type 불일치(415) 등 WebFlux 가 이미 적절한 상태코드를 정해준 예외는 그대로 돌려준다.
        // 여기를 500 으로 뭉개면 IdP 가 결코 성공할 수 없는 요청을 무한히 재시도하게 된다.
        if (error instanceof ResponseStatusException rse) {
            return write(HttpStatus.valueOf(rse.getStatusCode().value()), null, rse.getReason());
        }
        log.error("SCIM 요청 처리 중 예기치 않은 오류", error);
        return write(HttpStatus.INTERNAL_SERVER_ERROR, null, "내부 오류가 발생했습니다");
    }

    private static Mono<ServerResponse> write(HttpStatus status, String scimType, String detail) {
        ScimError body = new ScimError(List.of(ScimSchemas.ERROR),
                String.valueOf(status.value()), scimType, detail);
        return ServerResponse.status(status)
                .contentType(SCIM_JSON)
                .bodyValue(body);
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

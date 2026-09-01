package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.MutationsSuspendedException;
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
                                                            ScimGroupHandler groups) {
        return RouterFunctions.route()
                .POST("/scim/v2/Users", users::create)
                .GET("/scim/v2/Users/{id}", users::get)
                .PUT("/scim/v2/Users/{id}", users::replace)
                .PATCH("/scim/v2/Users/{id}", users::patch)
                .DELETE("/scim/v2/Users/{id}", users::delete)
                .POST("/scim/v2/Groups", groups::create)
                .GET("/scim/v2/Groups/{id}", groups::get)
                .PUT("/scim/v2/Groups/{id}", groups::replace)
                .PATCH("/scim/v2/Groups/{id}", groups::patch)
                .DELETE("/scim/v2/Groups/{id}", groups::delete)
                .GET("/scim/v2/ServiceProviderConfig", request -> serviceProviderConfig())
                .onError(Throwable.class, ScimRouter::toScimError)
                .build();
    }

    private static Mono<ServerResponse> toScimError(Throwable error, ServerRequest request) {
        if (error instanceof ScimException scim) {
            return write(scim.getStatus(), scim.getScimType(), scim.getMessage());
        }
        // 재적재가 도는 동안의 변경은 503 이다. IdP 는 503 을 재시도 신호로 보므로 프로비저닝이
        // 유실되지 않고, 재시도 시점에는 재적재가 끝난 깨끗한 상태 위에서 처리된다.
        // 400 이나 500 으로 뭉개면 IdP 가 영구 실패로 판단해 포기하거나 무한히 재시도한다.
        if (error instanceof MutationsSuspendedException suspended) {
            return write(HttpStatus.SERVICE_UNAVAILABLE, null, suspended.getMessage());
        }
        // 변경 락을 얻지 못했거나(다른 인스턴스가 쥐고 있음) 쓰기 직전에 리스를 잃은 경우도
        // 같은 이유로 503 이다 — IdP 가 재시도하면 락이 풀린 뒤 깨끗하게 처리된다.
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
     * 지원하지 않는 기능을 정직하게 광고한다. 여기서 filter 를 지원한다고 하면
     * IdP 가 필터 질의를 보내기 시작하고, 우리는 그것을 처리할 수 없다.
     */
    private static Mono<ServerResponse> serviceProviderConfig() {
        Map<String, Object> config = Map.of(
                "schemas", List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG),
                "patch", Map.of("supported", true),
                "bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0),
                "filter", Map.of("supported", false, "maxResults", 0),
                "changePassword", Map.of("supported", false),
                "sort", Map.of("supported", false),
                "etag", Map.of("supported", false),
                "authenticationSchemes", List.of());
        return ServerResponse.ok().contentType(SCIM_JSON).bodyValue(config);
    }
}

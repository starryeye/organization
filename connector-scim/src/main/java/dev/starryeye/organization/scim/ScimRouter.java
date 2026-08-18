package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
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
        // 본문 파싱 실패 등 SCIM 이 모르는 예외는 400 으로 번역한다.
        if (error instanceof DecodingException || error instanceof ServerWebInputException) {
            return write(HttpStatus.BAD_REQUEST, "invalidSyntax", "요청 본문을 해석할 수 없습니다");
        }
        log.error("SCIM 요청 처리 중 예기치 않은 오류", error);
        return write(HttpStatus.INTERNAL_SERVER_ERROR, null, "내부 오류가 발생했습니다");
    }

    private static Mono<ServerResponse> write(HttpStatus status, String scimType, String detail) {
        ScimError body = new ScimError(List.of(ScimSchemas.ERROR),
                String.valueOf(status.value()), scimType, detail);
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
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
        return ServerResponse.ok().bodyValue(config);
    }
}

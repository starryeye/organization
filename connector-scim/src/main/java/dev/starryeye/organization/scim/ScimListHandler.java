package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimListResponse;
import dev.starryeye.organization.scim.dto.ScimSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static dev.starryeye.organization.scim.ScimRouter.SCIM_JSON;

/**
 * 목록·{@code .search} 조회 (RFC 7644 §3.4.2, §3.4.3). 파라미터 검사에서 나는 예외는
 * {@code Mono.fromCallable} 로 {@code onError} 가 되어 {@link ScimRouter} 의 오류 번역을 탄다.
 */
@RequiredArgsConstructor
public class ScimListHandler {

    private final ScimUserListing users;
    private final ScimGroupListing groups;

    public Mono<ServerResponse> listUsers(ServerRequest request) {
        return Mono.fromCallable(() -> ScimQuery.fromRequest(ScimResourceType.USER, request))
                .flatMap(users::list)
                .flatMap(ScimListHandler::ok);
    }

    public Mono<ServerResponse> searchUsers(ServerRequest request) {
        return body(request)
                .map(search -> ScimQuery.fromSearch(ScimResourceType.USER, search))
                .flatMap(users::list)
                .flatMap(ScimListHandler::ok);
    }

    public Mono<ServerResponse> listGroups(ServerRequest request) {
        return Mono.fromCallable(() -> ScimQuery.fromRequest(ScimResourceType.GROUP, request))
                .flatMap(groups::list)
                .flatMap(ScimListHandler::ok);
    }

    public Mono<ServerResponse> searchGroups(ServerRequest request) {
        return body(request)
                .map(search -> ScimQuery.fromSearch(ScimResourceType.GROUP, search))
                .flatMap(groups::list)
                .flatMap(ScimListHandler::ok);
    }

    private static Mono<ScimSearchRequest> body(ServerRequest request) {
        return request.bodyToMono(ScimSearchRequest.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")));
    }

    private static Mono<ServerResponse> ok(ScimListResponse body) {
        return ServerResponse.ok().contentType(SCIM_JSON).bodyValue(body);
    }
}

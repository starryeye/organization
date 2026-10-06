package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimListResponse;
import dev.starryeye.organization.scim.dto.ScimSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.BodyInserters;
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
                .flatMap(this::groupsResponse);
    }

    public Mono<ServerResponse> searchGroups(ServerRequest request) {
        return body(request)
                .map(search -> ScimQuery.fromSearch(ScimResourceType.GROUP, search))
                .flatMap(this::groupsResponse);
    }

    /** 멤버가 응답에 남고 자원을 담는 쪽(count > 0)이면 흘려 쓴다(설계 2026-10-06 §4.3). 아니면 지금처럼 한 번에. */
    private Mono<ServerResponse> groupsResponse(ScimQuery query) {
        if (query.projection().includes("members") && query.count() > 0) {
            return groups.streamed(query).flatMap(body ->
                    ServerResponse.ok().contentType(SCIM_JSON).body(BodyInserters.fromDataBuffers(body)));
        }
        return groups.list(query).flatMap(ScimListHandler::ok);
    }

    private static Mono<ScimSearchRequest> body(ServerRequest request) {
        return request.bodyToMono(ScimSearchRequest.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")));
    }

    private static Mono<ServerResponse> ok(ScimListResponse body) {
        return ServerResponse.ok().contentType(SCIM_JSON).bodyValue(body);
    }
}

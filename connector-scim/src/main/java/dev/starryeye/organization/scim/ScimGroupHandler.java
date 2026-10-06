package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

import static dev.starryeye.organization.scim.ScimRouter.SCIM_JSON;

public class ScimGroupHandler {

    private final DirectoryStateRepository state;
    private final IncrementalSyncUseCase sync;
    private final MemberTypeResolver memberTypes;
    private final ScimGroupStream stream;

    public ScimGroupHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync, MemberTypeResolver memberTypes) {
        this.state = state;
        this.sync = sync;
        this.memberTypes = memberTypes;
        this.stream = new ScimGroupStream(state);
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return projection(request).flatMap(projection -> request.bodyToMono(ScimGroup.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                // id 는 서버가 발급한다 — RFC 7643 §3.1(설계 2026-10-04 §3.1). 본문의 id 는 쓰지 않는다
                .flatMap(scim -> ScimMapper.toDirectoryGroup(scim, UUID.randomUUID().toString(), memberTypes))
                // 같은 externalId 의 조직이 이미 있는지는 락 안에서 확인한다(SCIM 쓰기 락 설계 §3)
                .flatMap(group -> sync.createGroup(group)
                        .flatMap(result -> respond(HttpStatus.CREATED, group.id(), result, projection))));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> body(HttpStatus.OK, id, projection,
                ScimException.notFound("조직을 찾을 수 없습니다: " + id)));
    }

    /** PUT — 전체 교체로 처리한다(조직 멤버 PATCH 설계 §4). 존재 확인은 락 안에서 한다. */
    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimGroup.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(scim -> ScimMapper.toDirectoryGroup(scim, id, memberTypes))
                // 경로의 id 가 정본이다. 본문의 id·externalId 가 달라도 리소스를 옮기지 않는다.
                .map(group -> GroupChange.replacement(group.externalId(), group.displayName(), group.members()))
                .flatMap(change -> sync.changeGroup(id, change)
                        .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id))))
                .flatMap(result -> respond(HttpStatus.OK, id, result, projection)));
    }

    /**
     * 조직 PATCH — {@code attributes} 쿼리 파라미터가 있으면 RFC 7644 §3.5.2 의 MUST 대로 200 과 투영한 리소스를,
     * 없으면(excludedAttributes 만 있어도) 본문 없이 204 를 돌려준다(조직 멤버 PATCH 설계 §7, 최종 리뷰 F1). Entra 는 멤버
     * 전체를 담은 본문을 권하지 않는다. attributes 가 없으면 응답을 만들려고 멤버를 읽지 않는다. 잘못된 attributes·
     * excludedAttributes 는 지금처럼 쓰기 전에 400 이다. 부분 실패는 503 이다(`respond` 참고).
     */
    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        boolean withAttributes = request.queryParam("attributes").isPresent();
        return projection(request).flatMap(projection -> request.bodyToMono(ScimPatchOp.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(patch -> ScimPatchApplier.toGroupChange(patch, memberTypes))
                .flatMap(change -> sync.changeGroup(id, change)
                        .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id))))
                .flatMap(result -> withAttributes
                        ? respond(HttpStatus.OK, id, result, projection)
                        : (result.fullyApplied()
                                ? ServerResponse.noContent().build()
                                : Mono.error(ScimException.temporarilyUnavailable(
                                        "일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기)))));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        // 존재 확인은 락 안에서 한다 — 락 밖에서 조직 파티션을 통째로 읽지 않는다(SCIM 쓰기 락 설계 §3)
        return sync.removeGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.temporarilyUnavailable(
                                "일부 튜플 삭제에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기)));
    }

    /**
     * 부분 실패면 상태는 이미 커밋됐지만 응답은 503 + Retry-After 로 돌려 IdP 가 재시도하게 한다(설계 2026-10-05 §3.5).
     * PUT·PATCH 의 재시도는 같은 최종 상태를 목표로 하므로 이미 반영된 부분은 다음 diff 에서 자연히 제외된다.
     * POST 의 재시도는 새 리소스(새 UUID)를 만든다 — 실패한 새 조직은 저장하지 않으므로 externalId 가 같아도 409 가 아니고,
     * 첫 번째 id 로 쓴 튜플은 재적재(mode=tuples)만 지운다. 응답만 잃은 성공한 POST 의 재시도는 externalId 가 있으면 409 다.
     */
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result,
                                         ScimAttributeProjection projection) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.temporarilyUnavailable(
                    "일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기));
        }
        return body(status, id, projection, ScimException.internal("저장된 리소스를 다시 읽지 못했습니다: " + id));
    }

    /**
     * 조직 응답. 헤더를 먼저 읽는다 — 없으면 응답을 쓰기 전에 {@code missing}(GET 이면 404, 쓰기 직후면 500)이다.
     * {@code members} 가 남으면 멤버를 흘려 쓰고(설계 2026-10-06 §4, 점검 P5), 남지 않으면 지금처럼 이름표만 쓴다 —
     * Entra 가 늘 붙이는 조건이고, 쓰기 응답(create/replace, attributes 가 있는 patch)도 이 규칙을 따른다(S-1 설계 §4.5).
     */
    private Mono<ServerResponse> body(HttpStatus status, String id, ScimAttributeProjection projection,
                                      ScimException missing) {
        return state.findGroupHeader(id)
                .switchIfEmpty(Mono.error(missing))
                .flatMap(header -> projection.includes("members")
                        ? builder(status, id).body(BodyInserters.fromDataBuffers(stream.group(header, projection)))
                        : builder(status, id).bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimGroup(header)))));
    }

    /** 생성(201)이면 {@code Location} 을 단다 — RFC 7644 §3.3 SHALL(설계 2026-10-06 §5.3). */
    private static ServerResponse.BodyBuilder builder(HttpStatus status, String id) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(status).contentType(SCIM_JSON);
        if (status == HttpStatus.CREATED) {
            builder.location(URI.create(ScimMapper.groupLocation(id)));
        }
        return builder;
    }

    /** 응답에 담을 속성(RFC 7644 §3.9). 쓰기 전에 검사해 잘못된 파라미터로 상태가 바뀌지 않게 한다. */
    private static Mono<ScimAttributeProjection> projection(ServerRequest request) {
        return Mono.fromCallable(() -> ScimAttributeProjection.fromRequest(ScimResourceType.GROUP, request));
    }
}

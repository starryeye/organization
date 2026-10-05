package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import dev.starryeye.organization.scim.dto.ScimUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static dev.starryeye.organization.scim.ScimRouter.SCIM_JSON;

@RequiredArgsConstructor
public class ScimUserHandler {

    private final DirectoryStateRepository state;
    private final IncrementalSyncUseCase sync;

    public Mono<ServerResponse> create(ServerRequest request) {
        return projection(request).flatMap(projection -> request.bodyToMono(ScimUser.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                // id 는 서버가 발급한다 — RFC 7643 §3.1(설계 2026-10-04 §3.1). 본문의 id 는 쓰지 않는다
                .map(scim -> ScimMapper.toDirectoryUser(scim, UUID.randomUUID().toString()))
                // userName 중복은 락 안에서 확인한다(SCIM 쓰기 락 설계 §4)
                .flatMap(user -> sync.createUser(user)
                        .flatMap(result -> respond(HttpStatus.CREATED, user.id(), result, projection))));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .flatMap(user -> ServerResponse.ok().contentType(SCIM_JSON)
                        .bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(user))))));
    }

    /** PUT — 본문으로 통째로 교체한다. 직원 읽기·존재 확인·userName 중복 확인은 락 안에서 한다(SCIM 쓰기 락 설계 §3). */
    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimUser.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                // PUT 은 경로의 id 를 정본으로 삼는다. 본문의 id·userName 이 달라도 리소스를 옮기지 않는다.
                .map(scim -> ScimMapper.toDirectoryUser(scim, id))
                .flatMap(user -> sync.changeUser(id, before -> user)
                        .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id))))
                .flatMap(result -> respond(HttpStatus.OK, id, result, projection)));
    }

    /**
     * PATCH — 연산 적용은 락 안에서, 락을 잡은 뒤 읽은 직원에 한다(SCIM 쓰기 락 설계 §3). 락 밖에서 읽은 직원으로 계산하면
     * 동시에 온 비활성화를 되돌리거나 방금 지운 직원을 되살린다.
     */
    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimPatchOp.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(patch -> sync.changeUser(id, before -> ScimPatchApplier.applyToUser(before, patch))
                        .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id))))
                .flatMap(result -> respond(HttpStatus.OK, id, result, projection)));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        // 존재 확인은 락 안에서 한다 — 없으면 빈 결과다(SCIM 쓰기 락 설계 §3)
        return sync.removeUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.temporarilyUnavailable(
                                "일부 튜플 삭제에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기)));
    }

    /**
     * 부분 실패면 상태는 이미 커밋됐지만 응답은 503 + Retry-After 로 돌려 IdP 가 재시도하게 한다(설계 2026-10-05 §3.5).
     * PUT·PATCH 의 재시도는 같은 최종 상태를 목표로 하므로 이미 반영된 부분은 다음 diff 에서 자연히 제외된다.
     * POST 는 쓸 튜플이 없다(새 UUID 를 가리키는 조직이 아직 없다). 응답을 잃어 다시 온 POST 는 같은 userName 이라 409 다.
     */
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result,
                                         ScimAttributeProjection projection) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.temporarilyUnavailable(
                    "일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기));
        }
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.internal("저장된 리소스를 다시 읽지 못했습니다: " + id)))
                .flatMap(saved -> ServerResponse.status(status)
                        .contentType(SCIM_JSON)
                        .bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(saved)))));
    }

    /** 응답에 담을 속성(RFC 7644 §3.9). 쓰기 전에 검사해 잘못된 파라미터로 상태가 바뀌지 않게 한다. */
    private static Mono<ScimAttributeProjection> projection(ServerRequest request) {
        return Mono.fromCallable(() -> ScimAttributeProjection.fromRequest(ScimResourceType.USER, request));
    }
}

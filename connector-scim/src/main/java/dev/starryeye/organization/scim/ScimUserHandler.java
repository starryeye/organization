package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import dev.starryeye.organization.scim.dto.ScimUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class ScimUserHandler {

    private final DirectoryStateRepository state;
    private final IncrementalSyncUseCase sync;

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(ScimUser.class)
                .map(ScimMapper::toDirectoryUser)
                .flatMap(user -> state.findUser(user.id())
                        .flatMap(existing -> Mono.<DirectoryUser>error(ScimException.uniqueness(
                                "이미 존재하는 직원입니다: " + user.id())))
                        .switchIfEmpty(Mono.just(user)))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.CREATED, user.id(), result)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .flatMap(user -> ServerResponse.ok().bodyValue(ScimMapper.toScimUser(user)));
    }

    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .then(request.bodyToMono(ScimUser.class))
                .map(ScimMapper::toDirectoryUser)
                // PUT 은 경로의 id 를 정본으로 삼는다. 본문의 userName 이 달라도 리소스를 옮기지 않는다.
                .map(user -> new DirectoryUser(id, user.externalId(), user.userName(),
                        user.displayName(), user.email(), user.active()))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .zipWith(request.bodyToMono(ScimPatchOp.class))
                .map(both -> ScimPatchApplier.applyToUser(both.getT1(), both.getT2()))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .then(sync.removeUser(id))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.internal(
                                "일부 튜플 삭제에 실패했습니다. 재시도해 주세요: " + id)));
    }

    /**
     * 부분 실패면 상태는 이미 커밋됐지만 응답은 5xx 로 돌려 IdP 가 재시도하게 한다(설계 §7.2).
     * 재시도는 같은 최종 상태를 목표로 하므로 이미 반영된 부분은 다음 diff 에서 자연히 제외된다.
     */
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.internal(
                    "일부 튜플 적용에 실패했습니다. 재시도해 주세요: " + id));
        }
        return state.findUser(id)
                .flatMap(saved -> ServerResponse.status(status).bodyValue(ScimMapper.toScimUser(saved)));
    }
}

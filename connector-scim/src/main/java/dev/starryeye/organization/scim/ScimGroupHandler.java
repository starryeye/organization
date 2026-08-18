package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class ScimGroupHandler {

    private final DirectoryStateRepository state;
    private final IncrementalSyncUseCase sync;

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(ScimGroup.class)
                .map(ScimMapper::toDirectoryGroup)
                .flatMap(group -> state.findGroup(group.id())
                        .flatMap(existing -> Mono.<DirectoryGroup>error(ScimException.uniqueness(
                                "이미 존재하는 조직입니다: " + group.id())))
                        .switchIfEmpty(Mono.just(group)))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.CREATED, group.id(), result)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .flatMap(group -> ServerResponse.ok().bodyValue(ScimMapper.toScimGroup(group)));
    }

    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .then(request.bodyToMono(ScimGroup.class))
                .map(ScimMapper::toDirectoryGroup)
                // 경로의 조직코드가 정본이다. 본문의 externalId 가 달라도 리소스를 옮기지 않는다.
                .map(group -> new DirectoryGroup(id, group.externalId(),
                        group.displayName(), group.members()))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .zipWith(request.bodyToMono(ScimPatchOp.class))
                .map(both -> ScimPatchApplier.applyToGroup(both.getT1(), both.getT2()))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .then(sync.removeGroup(id))
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
        return state.findGroup(id)
                .flatMap(saved -> ServerResponse.status(status).bodyValue(ScimMapper.toScimGroup(saved)));
    }
}

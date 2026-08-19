package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 현재상태를 조회해 멤버의 종류를 판정한다. 조직을 먼저 찾고, 없으면 직원을 찾는다.
 *
 * <p>둘 다 없으면 User 로 둔다 — SCIM 은 아직 도착하지 않은 리소스를 먼저 참조할 수 있고,
 * 그 경우 뒤늦게 도착한 쪽이 {@code type} 을 명시하는 것이 정상적인 흐름이기 때문이다.
 * 어느 쪽으로 판정하든 경고를 남긴다. 판정이 틀리면 IdP 는 성공 응답을 받고도 의도한 것과
 * 다른 튜플을 얻게 되므로, 추측했다는 사실 자체가 로그에 남아야 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class StateMemberTypeResolver implements MemberTypeResolver {

    private final DirectoryStateRepository state;

    @Override
    public Mono<MemberType> resolve(String id) {
        return state.findGroup(id)
                .map(group -> MemberType.GROUP)
                .switchIfEmpty(Mono.defer(() -> state.findUser(id).map(user -> MemberType.USER)))
                .defaultIfEmpty(MemberType.USER)
                .doOnNext(type -> log.warn(
                        "SCIM 멤버에 type 이 없어 현재상태로 추정합니다: value='{}', 추정={}", id, type));
    }
}

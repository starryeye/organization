package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.MemberType;
import reactor.core.publisher.Mono;

/**
 * SCIM {@code members[].type} 이 없을 때 그 멤버가 직원인지 하위 조직인지 판정한다.
 *
 * <p>RFC 7643 에서 {@code type} 은 선택 필드지만, SCIM 이 중첩 조직을 표현할 수 있는 유일한
 * 수단이기도 하다. 조직코드와 직원 아이디는 서로 다른 네임스페이스라 겹칠 수 있으므로,
 * 없는 {@code type} 을 User 로 단정하면 IdP 가 조직을 중첩하려던 요청이
 * {@code (user:X, direct_member, group:부모)} 로 뒤바뀔 수 있다.
 */
@FunctionalInterface
public interface MemberTypeResolver {

    /** {@code id} 는 이미 {@code IdNormalizer} 를 통과한 값이어야 한다. */
    Mono<MemberType> resolve(String id);
}

package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 조직·직원·멤버십의 <b>현재</b> 상태. 튜플이 아니라 도메인 상태를 담는다.
 */
public interface DirectoryStateRepository {

    Mono<DirectoryUser> findUser(String userId);

    /**
     * {@code userName} 으로 직원 아이디를 찾는다.
     *
     * <p>{@link DirectoryUser#id()} 는 생성 시점의 {@code userName} 에서 파생되고 그 뒤의
     * {@code userName} 변경을 따라가지 않는다(SCIM 의 정체성은 {@code id} 다). 그래서 이름이
     * 바뀐 뒤 같은 사람이 새 {@code userName} 으로 다시 생성 요청되면 {@link #findUser} 로는
     * 못 찾고 같은 사람의 레코드가 둘 생긴다. 생성 시 중복 판정에 쓴다.
     */
    Flux<String> findUserIdsByUserName(String userName);

    Mono<DirectoryGroup> findGroup(String groupId);

    Mono<Void> saveUser(DirectoryUser user);

    /** 멤버십까지 포함해 교체한다. 기존 멤버십 중 사라진 것은 삭제된다. */
    Mono<Void> saveGroup(DirectoryGroup group);

    Mono<Void> deleteUser(String userId);

    Mono<Void> deleteGroup(String groupId);

    /** 역참조. SCIM 이 직원·조직을 삭제할 때 어느 조직의 튜플을 지워야 하는지 찾는다. */
    Flux<String> findGroupIdsContaining(MemberRef ref);

    /** LDAP 전체 동기화용. 스냅샷에 없는 기존 엔트리는 삭제된다. */
    Mono<Void> replaceWith(DirectorySnapshot snapshot);

    Mono<DirectorySnapshot> loadAll();
}

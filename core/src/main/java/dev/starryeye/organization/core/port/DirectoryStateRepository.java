package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
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

    /**
     * 멤버를 빼고 조직의 META 만 읽는다. <b>읽는 양이 조직 크기를 따라가지 않는다.</b>
     *
     * <p>직원 한 명에 대한 연산은 조직의 id 만 있으면 튜플을 만들 수 있는데,
     * {@link #findGroup} 은 파티션을 통째로 읽어 1,600명 조직이면 1,601 아이템을 가져온다.
     * {@link #findUser} 가 이미 같은 이유로 Query 대신 GetItem 을 쓴다.
     *
     * <p>조직이 없으면 빈 {@code Mono} 다 — {@link #findGroup} 이 그때 빈 것을 돌려주는
     * 것과 같은 뜻이므로, 부르는 쪽의 "없는 조직은 건너뛴다" 동작이 바뀌지 않는다.
     */
    Mono<GroupHeader> findGroupHeader(String groupId);

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

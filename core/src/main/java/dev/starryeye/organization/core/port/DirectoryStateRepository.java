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

    /**
     * 조직 {@code groupId} 의 멤버 목록에 {@code ref} 가 실제로 있는지, 멤버 줄 한 개만
     * 강한 일관성으로 확인한다.
     *
     * <p><b>왜 필요한가.</b> {@link #findGroupIdsContaining} 은 GSI1(최종 일관성)이라 멤버가
     * 방금 빠진 조직을 잠시 계속 보고할 수 있다. 예전에는 그 뒤 {@link #findGroup} 이 파티션을
     * 통째로 읽어 실제 멤버 목록으로 다시 걸렀으므로 그 낡은 히트가 조용히 걸러졌다. 지금은
     * {@link #findGroupHeader} 가 그 자리를 대신하는데, 헤더는 조직의 <b>존재</b>만 확인하고
     * <b>멤버십</b>은 확인하지 않는다 — 멤버가 빠져도 META 아이템은 그대로 남기 때문이다.
     * 그래서 존재 확인과 멤버십 확인을 각자의 강한 일관성 읽기로 나눈다. 이걸 건너뛰면
     * "조직에서 막 빠진 직원의 PUT 재시도"가 이미 지워진 멤버십을 되살려 쓴다 — 권한
     * 있는 시스템에서 가장 위험한 방향의 오류다.
     *
     * <p>비용은 조직 크기와 무관하다 — {@link #findGroup} 처럼 파티션 전체를 읽지 않고
     * 멤버 줄 한 개만 {@code GetItem} 한다.
     */
    Mono<Boolean> containsMember(String groupId, MemberRef ref);

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

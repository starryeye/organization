package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 조직·직원·멤버십의 <b>현재</b> 상태. 튜플이 아니라 도메인 상태를 담는다.
 */
public interface DirectoryStateRepository {

    Mono<DirectoryUser> findUser(String userId);

    /**
     * {@code userName} 으로 직원 아이디를 찾는다. <b>대소문자를 가리지 않는다</b> — RFC 7643 은
     * {@code userName} 을 {@code caseExact=false} 로 정한다. SCIM 생성의 409 중복 판정이 이 성질에
     * 기댄다: 대소문자만 다른 {@code userName} 으로 다시 만들려 하면 여기서 걸려야 한다.
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

    /**
     * 조직을 지운다 — 상위 조직들의 "이 조직" 멤버 줄과 이 조직의 멤버 줄을 먼저, 이 조직의 소속 줄과 멤버들의 소속 줄을 그다음, META 를 맨 마지막에
     * (설계 2026-10-02 §4.1). 멤버는 호출자가 이미 읽은 것을 받는다 — 조직 파티션을 다시 훑지 않는다.
     *
     * <p><b>META 가 맨 마지막이다.</b> 중간에 멈추면(오류·리스 상실) 조직이 남아, 같은 삭제를 다시 부르면 남은 것을 마저 지운다. 멤버 줄을 소속 줄보다
     * 먼저 지운다 — 소속 줄만 남는 방향으로만 어긋난다(강한 소속 조회 설계 §5). 중간에 멈추면 이미 멤버 줄을 지운 멤버의 소속 줄은 다시 지워도
     * 찾지 못해 남을 수 있다 — 소속 줄만 남는 안전한 방향이라 역참조(멤버 줄로 확인)와 권한에는 영향이 없고 저장 공간만 남는다.
     */
    Mono<Void> deleteGroup(String groupId, Set<MemberRef> members);

    /**
     * 역참조 — 이 멤버가 속한 조직들. SCIM 이 직원·조직을 삭제하거나 상위 조직을 찾을 때 쓴다.
     *
     * <p><b>강한 일관성이고 정확하다.</b> 구현은 멤버 쪽 파티션의 소속 줄을 읽고, 조직 쪽 멤버 줄이
     * 실제로 있는지 확인한 것만 돌려준다. 최종 일관성 인덱스를 쓰면 막 추가된 소속을 놓쳐 삭제가
     * 권한을 남긴다(설계 `2026-09-16-strong-membership-lookup-design.md` §1).
     */
    Flux<String> findGroupIdsContaining(MemberRef ref);

    /**
     * 주어진 멤버 중 지금 이 조직의 멤버인 것. <b>읽는 양이 조직 크기가 아니라 후보 수를 따른다</b> — 조직 멤버 PATCH 가 멤버 한 명을
     * 바꿀 때 조직 전체를 읽지 않으려고 쓴다(설계 `2026-09-26-group-member-patch-design.md` §6). 강한 일관성이다.
     */
    Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates);

    /** 이 조직의 멤버 전부를 <b>키만</b> 읽는다. 전체 교체가 목표 목록과 비교하는 데 쓴다. 강한 일관성이다. */
    Flux<MemberRef> findMemberRefs(String groupId);

    /** 이 조직의 하위 조직 id. 직원 멤버는 읽지 않는다 — 순환 검사가 계층을 내려갈 때 쓴다. 조직이 없으면 비어 있다. */
    Flux<String> findChildGroupIds(String groupId);

    /**
     * 멤버 줄을 {@code added} 만큼 넣고 {@code removed} 만큼 빼고 META 를 {@code header} 로 맞춘다. {@link #saveGroup} 과 같은 규칙이다 —
     * 넣을 때는 소속 줄 먼저, 뺄 때는 멤버 줄 먼저, META 는 이름이나 멤버가 바뀌었을 때만 {@code updatedAt} 을 찍는다. 부르는 쪽이
     * {@code added} 가 지금 멤버가 아니고 {@code removed} 가 지금 멤버라는 것을 확인했다고 본다(락 안에서 {@link #findMembers} 로).
     */
    Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed);

    /** LDAP 전체 동기화용. 스냅샷에 없는 기존 엔트리는 삭제된다. */
    Mono<Void> replaceWith(DirectorySnapshot snapshot);

    Mono<DirectorySnapshot> loadAll();
}

package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 조직·직원·멤버십의 현재 상태를 단일 테이블에 저장하는 {@link DirectoryStateRepository} 구현체.
 * 튜플이 아니라 도메인 상태만 다룬다 — 실제로 OpenFGA 에 반영된 튜플은 별도 저장소(Task 9)가 담당한다.
 *
 * <h2>강한 일관성</h2>
 *
 * <p>메인 테이블 읽기({@link #findUser}, {@link #findGroup} 및 그 아래 {@code queryPartition})는
 * {@code consistentRead} 를 켠다.
 *
 * <p><b>왜 필요해졌나 (설계 §5).</b> 전에는 diff 의 양쪽 재료가 모두 이 저장소였다. 낡은 값을
 * 읽어도 before 와 after 가 똑같이 낡아 델타가 비었을 뿐, 틀린 튜플을 쓰지는 않았다. 지금은
 * {@code after} 만 여기서 오고 기준선은 OpenFGA 에서 온다 — <b>낡은 읽기가 곧 삭제</b>가 된다:
 *
 * <pre>
 * 인스턴스 A: PUT /Users/bob {active:true}  → dm(bob,DEV001) 쓰고 락 반납
 * 인스턴스 B: PUT /Groups/DEV001            → bob 을 낡은 비활성으로 읽음
 *                                          → after 에 dm(bob,DEV001) 이 없음
 *                                          → 방금 쓴 튜플을 지운다
 * </pre>
 *
 * <p><b>역참조({@link #findGroupIdsContaining})도 강한 일관성이고 정확하다.</b> 멤버 쪽 파티션의
 * 소속 줄을 강한 일관성으로 읽고, 조직 쪽 멤버 줄이 실제로 있는지까지 이 메서드 안에서 확인한
 * 것만 돌려준다(설계 `2026-09-16-strong-membership-lookup-design.md` §1, §6) — 막 추가된 멤버십을
 * 놓쳐 삭제가 권한을 남기는 방향도, 중간 실패로 소속 줄만 남아 "속하지 않은 조직" 이 보이는
 * 방향도 여기서 막힌다.
 *
 * <p>비용은 읽기당 RCU 2배다 — 멤버 확인이 추가로 붙는 경로는 쓰기 경로 정도로 요청당 한
 * 자릿수라 감당할 만하고, 조회 API 는 별도 저장소({@code DynamoDbDirectorySearchRepository})를
 * 탄다.
 */
@RequiredArgsConstructor
public class DynamoDbDirectoryStateRepository implements DirectoryStateRepository {

    private static final int QUERY_CONCURRENCY = 8;

    private static final String EXTERNAL_ID = "externalId";
    private static final String USER_NAME = "userName";
    private static final String DISPLAY_NAME = "displayName";
    private static final String EMAIL = "email";
    private static final String ACTIVE = "active";
    private static final String UPDATED_AT = "updatedAt";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    /** 형제 저장소 둘과 맞춘다. 고정 시계를 넣어야 updatedAt/addedAt 을 테스트할 수 있다. */
    private final Clock clock;

    // ---------- 직원 ----------

    /**
     * PK 와 SK 를 모두 알고 있으므로 {@code GetItem} 으로 한 건만 집어온다.
     * 전에는 파티션 전체를 Query 로 읽고 클라이언트에서 META 만 골라냈다 — 결과는 같지만
     * 읽는 양과 소비 RCU 가 파티션 크기를 따라간다. 조회 API 가 직원 단건을 자주 부른다.
     *
     * <p><b>강한 일관성으로 읽는다.</b> 클래스 자바독의 "강한 일관성" 절 참고.
     */
    @Override
    public Mono<DirectoryUser> findUser(String userId) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.userPk(userId)),
                                Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(response -> toUser(userId, response.item()));
    }

    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.userPk(user.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.USER_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(user.userName() == null ? user.id() : user.userName()));
        // GSI2(표시명 검색)를 위해 따로 쓸 것이 없다 — 파티션키는 위의 GSI1PK 를 그대로 쓰고
        // 정렬키는 아래 putIfPresent 가 쓰는 displayName 속성 그 자체다(Keys.GSI2PK 참고).
        // 표시명이 없는 직원은 그 속성이 아예 없어 GSI2 에 실리지 않는다 — DynamoDB 는 정렬키
        // 속성이 없는 아이템을 인덱스에 넣지 않는다. 의도한 동작이며, 아이디·계정명으로는
        // 여전히 찾힌다.
        item.put(ACTIVE, Attrs.bool(user.active()));
        item.put(UPDATED_AT, Attrs.s(Instant.now(clock).toString()));
        Attrs.putIfPresent(item, EXTERNAL_ID, user.externalId());
        Attrs.putIfPresent(item, USER_NAME, user.userName());
        Attrs.putIfPresent(item, DISPLAY_NAME, user.displayName());
        Attrs.putIfPresent(item, EMAIL, user.email());

        return putItem(item);
    }

    /** 직원 파티션을 통째로 비운다 — {@code META} 와 남아 있을 수 있는 소속 줄까지. */
    @Override
    public Mono<Void> deleteUser(String userId) {
        return queryPartition(Keys.userPk(userId))
                .map(item -> Attrs.str(item, Keys.SK))
                .flatMap(sk -> deleteItem(Keys.userPk(userId), sk), QUERY_CONCURRENCY)
                .then();
    }

    /**
     * {@link #saveUser} 가 GSI1 의 정렬키에 {@code userName} 을 넣어 두므로 Scan 없이
     * 정확 일치 Query 로 찾을 수 있다.
     */
    @Override
    public Flux<String> findUserIdsByUserName(String userName) {
        if (userName == null || userName.isBlank()) {
            return Flux.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk AND #sk = :sk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK, "#sk", Keys.GSI1SK))
                .expressionAttributeValues(Map.of(
                        ":pk", Attrs.s(Keys.USER_INDEX), ":sk", Attrs.s(userName)))
                .build();

        return Paginator.queryAll(client, request).map(item -> Keys.parseUserPk(Attrs.str(item, Keys.PK)));
    }

    private DirectoryUser toUser(String userId, Map<String, AttributeValue> item) {
        return new DirectoryUser(
                userId,
                Attrs.str(item, EXTERNAL_ID),
                Attrs.str(item, USER_NAME),
                Attrs.str(item, DISPLAY_NAME),
                Attrs.str(item, EMAIL),
                Attrs.flag(item, ACTIVE));
    }

    // ---------- 조직 ----------

    /** <b>강한 일관성으로 읽는다.</b> 클래스 자바독의 "강한 일관성" 절 참고. */
    @Override
    public Mono<DirectoryGroup> findGroup(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .collectList()
                .flatMap(items -> Mono.justOrEmpty(toGroup(groupId, items)));
    }

    /**
     * PK 와 SK 를 모두 알고 있으므로 {@code GetItem} 으로 META 한 건만 집어온다 —
     * {@link #findUser} 와 같은 이유다. 읽는 양이 조직 크기를 따라가지 않는다.
     *
     * <p><b>강한 일관성으로 읽는다.</b> 클래스 자바독의 "강한 일관성" 절 참고.
     */
    @Override
    public Mono<GroupHeader> findGroupHeader(String groupId) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.groupPk(groupId)),
                                Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(response -> new GroupHeader(groupId,
                        Attrs.str(response.item(), EXTERNAL_ID),
                        Attrs.str(response.item(), DISPLAY_NAME)));
    }

    /**
     * PK 와 SK 를 모두 알고 있으므로 {@code GetItem} 으로 멤버 줄 한 개만 집어온다 —
     * {@link #findGroupHeader} 와 같은 이유다. 읽는 양이 조직 크기를 따라가지 않는다.
     *
     * <p><b>강한 일관성으로 읽는다.</b> {@link #findGroupIdsContaining} 이 소속 줄에서 뽑은
     * 후보 조직마다 이 메서드로 멤버 줄 자체를 다시 확인한다 — 쓰기가 중간에 실패해 소속
     * 줄만 남은 경우를 걸러낸다.
     *
     * <p>포트에는 없다 — {@link #findGroupIdsContaining} 내부에서만 쓰는 확인 단계라 패키지
     * 전용이다. 다만 그 확인 자체를 지키는 기존 테스트 세 줄이 이 메서드를 직접 부르므로
     * {@code private} 로 좁히지 않는다.
     */
    Mono<Boolean> containsMember(String groupId, MemberRef ref) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.groupPk(groupId)),
                                Keys.SK, Attrs.s(Keys.memberSk(ref))))
                        .consistentRead(true)
                        .build()))
                .map(GetItemResponse::hasItem);
    }

    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        Map<String, AttributeValue> meta = new HashMap<>();
        meta.put(Keys.PK, Attrs.s(Keys.groupPk(group.id())));
        meta.put(Keys.SK, Attrs.s(Keys.META));
        meta.put(Keys.GSI1PK, Attrs.s(Keys.GROUP_INDEX));
        meta.put(Keys.GSI1SK, Attrs.s(group.displayName() == null ? group.id() : group.displayName()));
        meta.put(UPDATED_AT, Attrs.s(Instant.now(clock).toString()));
        Attrs.putIfPresent(meta, EXTERNAL_ID, group.externalId());
        Attrs.putIfPresent(meta, DISPLAY_NAME, group.displayName());

        Set<String> targetSks = group.members().stream().map(Keys::memberSk).collect(Collectors.toSet());

        return existingMemberSks(group.id())
                .collectList()
                .flatMap(existing -> {
                    Set<String> existingSks = Set.copyOf(existing);
                    List<String> 떠난멤버 = existing.stream()
                            .filter(sk -> !targetSks.contains(sk))
                            .toList();
                    // 이미 있는 멤버는 건드리지 않는다. 다시 put 하면 addedAt 이 덮여
                    // "최초 합류" 가 아니라 "마지막 전체 동기화" 를 뜻하게 된다.
                    // 나머지 속성(GSI 키)은 groupId·member 로만 정해져 바뀔 것이 없다.
                    List<MemberRef> 새로온멤버 = group.members().stream()
                            .filter(member -> !existingSks.contains(Keys.memberSk(member)))
                            .toList();

                    // 소속 줄이 항상 멤버 줄보다 많거나 같게 유지한다(설계 §5).
                    // 넣을 때는 소속 줄 먼저, 뺄 때는 멤버 줄 먼저 — 중간에 실패해도
                    // "소속 줄만 남는" 안전한 방향으로만 어긋난다. 반대로 어긋나면
                    // 삭제가 그 조직을 못 찾아 권한이 남는다.
                    return Flux.fromIterable(떠난멤버)
                            .map(Keys::parseMemberSk)
                            .flatMap(ref -> deleteItem(Keys.groupPk(group.id()), Keys.memberSk(ref))
                                    .then(deleteItem(Keys.memberPk(ref), Keys.belongsToSk(group.id()))),
                                    QUERY_CONCURRENCY)
                            .then(putItem(meta))
                            .then(Flux.fromIterable(새로온멤버)
                                    .flatMap(member -> putItem(belongsToItem(member, group.id()))
                                            .then(putItem(memberItem(group.id(), member))),
                                            QUERY_CONCURRENCY)
                                    .then());
                });
    }

    /**
     * 조직 파티션을 비우고, <b>그 멤버들의 소속 줄까지</b> 지운다. 소속 줄을 남기면 역참조가
     * 그 조직을 후보로 계속 들고 오고(확인 단계가 걸러 내지만) 파티션에 영원히 쌓인다.
     */
    @Override
    public Mono<Void> deleteGroup(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .map(item -> Attrs.str(item, Keys.SK))
                .collectList()
                .flatMap(sks -> {
                    List<MemberRef> members = sks.stream()
                            .filter(Keys::isMemberSk)
                            .map(Keys::parseMemberSk)
                            .toList();
                    return Flux.fromIterable(sks)
                            .flatMap(sk -> deleteItem(Keys.groupPk(groupId), sk), QUERY_CONCURRENCY)
                            .thenMany(Flux.fromIterable(members))
                            .flatMap(ref -> deleteItem(Keys.memberPk(ref), Keys.belongsToSk(groupId)),
                                    QUERY_CONCURRENCY)
                            .then();
                });
    }

    private Map<String, AttributeValue> memberItem(String groupId, MemberRef member) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.groupPk(groupId)));
        item.put(Keys.SK, Attrs.s(Keys.memberSk(member)));
        item.put("addedAt", Attrs.s(Instant.now(clock).toString()));
        return item;
    }

    /**
     * 멤버 쪽 파티션에 적는 소속 줄. <b>GSI 키를 넣지 않는다</b> — 넣으면 직원·조직 열거
     * ({@code USER_INDEX}/{@code GROUP_INDEX})와 조회 API 결과에 섞인다.
     */
    private Map<String, AttributeValue> belongsToItem(MemberRef member, String groupId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.memberPk(member)));
        item.put(Keys.SK, Attrs.s(Keys.belongsToSk(groupId)));
        item.put("addedAt", Attrs.s(Instant.now(clock).toString()));
        return item;
    }

    private Flux<String> existingMemberSks(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .map(item -> Attrs.str(item, Keys.SK))
                .filter(Keys::isMemberSk);
    }

    private DirectoryGroup toGroup(String groupId, List<Map<String, AttributeValue>> items) {
        Map<String, AttributeValue> meta = items.stream()
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .findFirst()
                .orElse(null);
        if (meta == null) {
            return null;
        }
        Set<MemberRef> members = items.stream()
                .map(item -> Attrs.str(item, Keys.SK))
                .filter(Keys::isMemberSk)
                .map(Keys::parseMemberSk)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new DirectoryGroup(groupId, Attrs.str(meta, EXTERNAL_ID), Attrs.str(meta, DISPLAY_NAME), members);
    }

    // ---------- 역참조 ----------

    /**
     * 멤버 쪽 파티션의 소속 줄을 <b>강한 일관성</b>으로 읽고, 조직 쪽 멤버 줄이 실제로 있는지
     * 확인한 것만 돌려준다.
     *
     * <p><b>GSI 를 쓰지 않는다.</b> GSI1 은 최종 일관성이라 막 추가된 멤버십을 아직 모를 수 있고,
     * 그 창에 삭제가 들어오면 그 조직의 튜플과 멤버 줄이 남는다(설계 §1).
     *
     * <p><b>확인까지 여기서 한다.</b> 쓰기가 중간에 실패하면 소속 줄만 남을 수 있다. 부르는 쪽에
     * 확인을 맡기면 관리자 조회처럼 그대로 믿는 곳에서 "속하지 않은 조직" 이 보인다(설계 §6).
     *
     * <p><b>소스 순서(정렬키 오름차순)를 지킨다 — {@code flatMap} 이 아니라
     * {@code flatMapSequential} 이다.</b> 본문 테이블 Query 는 정렬키 오름차순으로 결정적으로
     * 돌아오는데, 확인을 병렬로 걸면서 {@code flatMap} 을 쓰면 방출 순서가 GetItem 완료 순서로
     * 바뀐다. {@code AdminQueryUseCase.directGroupsOf} 는 이 메서드가 낸 순서 그대로
     * {@code take(MAX_PATHS+1)} 로 자르고, {@code expandParents}/{@code ancestorsOf} 는 이
     * 순서를 상위 조직 목록 순서로 그대로 넘긴다 — 둘 다 소스 순서가 안정적이라고 전제한다.
     */
    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(
                        ":pk", Attrs.s(Keys.memberPk(ref)),
                        ":prefix", Attrs.s(Keys.BELONGS_TO_PREFIX)))
                .consistentRead(true)
                .build();

        return Paginator.queryAll(client, request)
                .map(item -> Keys.parseBelongsToSk(Attrs.str(item, Keys.SK)))
                .flatMapSequential(groupId -> containsMember(groupId, ref)
                        .filter(Boolean::booleanValue)
                        .map(confirmed -> groupId), QUERY_CONCURRENCY);
    }

    // ---------- 전체 ----------

    @Override
    public Mono<Void> replaceWith(DirectorySnapshot snapshot) {
        Mono<Void> removeStaleUsers = enumerateIds(Keys.USER_INDEX, Keys::parseUserPk)
                .filter(id -> !snapshot.users().containsKey(id))
                .flatMap(this::deleteUser, QUERY_CONCURRENCY)
                .then();

        Mono<Void> removeStaleGroups = enumerateIds(Keys.GROUP_INDEX, Keys::parseGroupPk)
                .filter(id -> !snapshot.groups().containsKey(id))
                .flatMap(this::deleteGroup, QUERY_CONCURRENCY)
                .then();

        Mono<Void> upsertUsers = Flux.fromIterable(snapshot.users().values())
                .flatMap(this::saveUser, QUERY_CONCURRENCY)
                .then();

        Mono<Void> upsertGroups = Flux.fromIterable(snapshot.groups().values())
                .flatMap(this::saveGroup, QUERY_CONCURRENCY)
                .then();

        return removeStaleUsers.then(removeStaleGroups).then(upsertUsers).then(upsertGroups);
    }

    @Override
    public Mono<DirectorySnapshot> loadAll() {
        Mono<Map<String, DirectoryUser>> users = enumerateIds(Keys.USER_INDEX, Keys::parseUserPk)
                .flatMap(this::findUser, QUERY_CONCURRENCY)
                .collect(LinkedHashMap::new, (map, user) -> map.put(user.id(), user));

        Mono<Map<String, DirectoryGroup>> groups = enumerateIds(Keys.GROUP_INDEX, Keys::parseGroupPk)
                .flatMap(this::findGroup, QUERY_CONCURRENCY)
                .collect(LinkedHashMap::new, (map, group) -> map.put(group.id(), group));

        return Mono.zip(users, groups)
                .map(both -> new DirectorySnapshot(both.getT1(), both.getT2()));
    }

    /** GSI1 파티션을 훑어 PK 에서 id 만 뽑는다. Scan 을 쓰지 않는 이유는 스펙 §6.1 참고. */
    private Flux<String> enumerateIds(String indexPartition, Function<String, String> parsePk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(indexPartition)))
                .build();

        return Paginator.queryAll(client, request).map(item -> parsePk.apply(Attrs.str(item, Keys.PK)));
    }

    // ---------- 공통 ----------

    /** 메인 테이블의 파티션 하나를 <b>강한 일관성</b>으로 읽는다. 클래스 자바독 참고. */
    private Flux<Map<String, AttributeValue>> queryPartition(String pk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request);
    }
    private Mono<Void> putItem(Map<String, AttributeValue> item) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item)
                .build())).then();
    }

    private Mono<Void> deleteItem(String pk, String sk) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                .build())).then();
    }
}

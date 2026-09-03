package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
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
 * <p><b>이것으로 창이 완전히 닫히지는 않는다.</b> DynamoDB GSI 는 강한 일관성을 지원하지 않으므로
 * 역참조({@link #findGroupIdsContaining})는 여전히 최종 일관성이다. 방금 추가된 멤버십이 GSI 에
 * 아직 안 보이면 그 조직이 영향 범위에서 빠질 수 있다 — 다만 그 경우는 "이 연산이 그 조직을
 * 건드리지 않는다" 로 끝나고, 위처럼 <b>있는 튜플을 지우는</b> 방향은 아니다. 남은 잔여 위험이며
 * 재적재가 유일한 해결책이다(설계 §5.4).
 *
 * <p>비용은 읽기당 RCU 2배다. 쓰기 경로의 읽기는 요청당 한 자릿수라 감당할 만하고, 조회 API 는
 * 별도 저장소({@code DynamoDbDirectorySearchRepository})를 탄다.
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

    @Override
    public Mono<Void> deleteUser(String userId) {
        return deleteItem(Keys.userPk(userId), Keys.META);
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

                    return Flux.fromIterable(떠난멤버)
                            .flatMap(sk -> deleteItem(Keys.groupPk(group.id()), sk), QUERY_CONCURRENCY)
                            .then(putItem(meta))
                            .then(Flux.fromIterable(새로온멤버)
                                    .flatMap(member -> putItem(memberItem(group.id(), member)),
                                            QUERY_CONCURRENCY)
                                    .then());
                });
    }

    @Override
    public Mono<Void> deleteGroup(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .map(item -> Attrs.str(item, Keys.SK))
                .flatMap(sk -> deleteItem(Keys.groupPk(groupId), sk), QUERY_CONCURRENCY)
                .then();
    }

    private Map<String, AttributeValue> memberItem(String groupId, MemberRef member) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.groupPk(groupId)));
        item.put(Keys.SK, Attrs.s(Keys.memberSk(member)));
        item.put(Keys.GSI1PK, Attrs.s(Keys.memberGsi1Pk(member)));
        item.put(Keys.GSI1SK, Attrs.s(Keys.groupPk(groupId)));
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

    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.memberGsi1Pk(ref))))
                .build();

        return Paginator.queryAll(client, request).map(item -> Keys.parseGroupPk(Attrs.str(item, Keys.PK)));
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

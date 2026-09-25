package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.PersonName;
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
import java.util.Optional;
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
 * 자릿수라 감당할 만하다. 목록·검색 조회 API 는 별도 저장소({@code DynamoDbDirectorySearchRepository})를
 * 타지만, {@code AdminQueryUseCase.employeeDetail} 은 이 저장소의 {@link #findUser} 와
 * {@link #findGroupIdsContaining} 을 그대로 써서 이제 후보 조직마다 강한 일관성 Query 한 번에
 * 확인 {@code GetItem} 한 번씩을 추가로 낸다.
 */
@RequiredArgsConstructor
public class DynamoDbDirectoryStateRepository implements DirectoryStateRepository {

    private static final int QUERY_CONCURRENCY = 8;

    private static final String EXTERNAL_ID = "externalId";
    private static final String USER_NAME = "userName";
    private static final String DISPLAY_NAME = "displayName";
    private static final String EMAIL = "email";
    /** 이름 여섯 칸(S-3 설계 §4). {@code formatted} 만 이름을 바꾼 것은 {@code displayName} 과 헷갈리지 않게 하려는 것이다. */
    private static final String GIVEN_NAME = "givenName";
    private static final String FAMILY_NAME = "familyName";
    private static final String MIDDLE_NAME = "middleName";
    private static final String HONORIFIC_PREFIX = "honorificPrefix";
    private static final String HONORIFIC_SUFFIX = "honorificSuffix";
    private static final String NAME_FORMATTED = "nameFormatted";
    private static final String ACTIVE = "active";
    /** 마지막 <b>변경</b> 시각. 바뀐 META 에만 찍는다(GSI 설계 §3). */
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

    /**
     * 저장된 META 와 다를 때만 쓰고, 그때만 {@code updatedAt} 을 찍는다(GSI 설계 §3). 같은 값을 다시 쓰면 GSI1(ALL
     * 프로젝션)이 매번 {@code updatedAt} 때문에 다시 쓰여 {@code USER_INDEX} 한 파티션키로 몰렸다.
     *
     * <p>저장본은 <b>강한 일관성</b>으로 한 건 읽는다 — SCIM 요청 하나의 쓰기 경로라 한 건 더 읽어도 싸다.
     */
    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        return findMeta(Keys.userPk(user.id()))
                .map(this::storedUser)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(stored -> writeUser(user, stored.orElse(null)));
    }

    /**
     * 저장본과 같으면 쓰지 않는다. 다르거나 없으면 {@code updatedAt} 을 찍어 쓴다.
     *
     * <p>{@code user} 를 그대로 비교하지 않고 {@code userItem} 으로 한 번 인코딩했다가 다시
     * {@code toUser} 로 읽어(round-trip) 비교한다 — 빈 문자열은 애초에 속성으로 저장되지
     * 않으므로({@link Attrs#putIfPresent}) 저장본을 되읽으면 {@code null} 이 된다. 들어온
     * {@code user} 가 빈 문자열을 그대로 들고 있으면 라운드트립 없이는 "저장했다면 나왔을 값"과
     * 영원히 달라 보여 매번 다시 쓴다 — 이 태스크가 없애려는 바로 그 재기록이다.
     */
    private Mono<Void> writeUser(DirectoryUser user, Stored<DirectoryUser> stored) {
        if (stored != null && stored.sameAs(toUser(user.id(), userItem(user)))) {
            return Mono.empty();
        }
        return putItem(stamped(userItem(user)));
    }

    /** 직원 META 에 쓸 아이템. {@code updatedAt} 은 넣지 않는다 — 바뀌었을 때만 {@link #stamped} 가 넣는다. */
    private Map<String, AttributeValue> userItem(DirectoryUser user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.userPk(user.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.USER_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(Keys.indexKey(presentOr(user.userName(), user.id()))));
        // GSI2(표시명 검색)를 위해 따로 쓸 것이 없다 — 파티션키는 위의 GSI1PK 를 그대로 쓰고
        // 정렬키는 아래 putIfPresent 가 쓰는 displayName 속성 그 자체다(Keys.GSI2PK 참고).
        // 표시명이 없는 직원은 그 속성이 아예 없어 GSI2 에 실리지 않는다 — DynamoDB 는 정렬키
        // 속성이 없는 아이템을 인덱스에 넣지 않는다. 의도한 동작이며, 아이디·계정명으로는
        // 여전히 찾힌다.
        item.put(ACTIVE, Attrs.bool(user.active()));
        Attrs.putIfPresent(item, EXTERNAL_ID, user.externalId());
        Attrs.putIfPresent(item, USER_NAME, user.userName());
        Attrs.putIfPresent(item, DISPLAY_NAME, user.displayName());
        Attrs.putIfPresent(item, EMAIL, user.email());
        PersonName name = user.name();
        Attrs.putIfPresent(item, NAME_FORMATTED, name.formatted());
        Attrs.putIfPresent(item, FAMILY_NAME, name.familyName());
        Attrs.putIfPresent(item, GIVEN_NAME, name.givenName());
        Attrs.putIfPresent(item, MIDDLE_NAME, name.middleName());
        Attrs.putIfPresent(item, HONORIFIC_PREFIX, name.honorificPrefix());
        Attrs.putIfPresent(item, HONORIFIC_SUFFIX, name.honorificSuffix());
        return item;
    }

    /**
     * 직원 파티션을 통째로 비우기 전에, 그 소속 줄이 가리키는 조직들의 멤버 줄부터 지운다.
     * {@link #deleteGroup} 과 대칭이다 — 그쪽은 조직 파티션(멤버 줄)을 먼저 비우고 멤버의
     * 소속 줄을 나중에 지우는데, 이쪽은 반대 방향이라 조직 쪽 멤버 줄을 먼저 지우고 직원
     * 파티션(소속 줄 포함)을 나중에 비운다.
     *
     * <p><b>순서가 이래야 하는 이유.</b> 지켜야 할 불변식은 "{@code MEMBER#} 줄은 반드시
     * {@code BELONGS_TO#} 줄을 동반한다" 다 — 거꾸로(소속 줄만 있고 멤버 줄이 없음)는
     * {@link #findGroupIdsContaining} 의 확인 단계가 걸러내 무해하다. 순서를 반대로 해서
     * 직원 파티션(소속 줄)을 먼저 비우면, 중간에 실패했을 때 그 조직 쪽엔 소속 줄 없는
     * {@code MEMBER#} 줄이 남는다 — 역참조가 이 직원을 놓쳐 나중에 그 조직을 지워도 권한이
     * 남는, 금지된 방향이다. 조직 쪽 멤버 줄을 먼저 지우면 중간 실패의 최악의 잔여물이
     * "멤버 줄 없는 소속 줄"(안전한 방향)뿐이다.
     *
     * <p>이 대칭이 깨지면 고칠 방법도 없다 — {@link #saveGroup} 은 "새로 온 멤버"를 조직 쪽
     * 파티션({@code existingMemberSks})만 보고 계산하므로, 소속 줄이 없어진 멤버 줄은
     * 재동기화로도 다시 쓰이지 않는다(설계 §5).
     */
    @Override
    public Mono<Void> deleteUser(String userId) {
        return queryPartition(Keys.userPk(userId))
                .map(item -> Attrs.str(item, Keys.SK))
                .collectList()
                .flatMap(sks -> {
                    List<String> groupIds = sks.stream()
                            .filter(Keys::isBelongsToSk)
                            .map(Keys::parseBelongsToSk)
                            .toList();
                    MemberRef self = MemberRef.user(userId);
                    return Flux.fromIterable(groupIds)
                            .flatMap(groupId -> deleteItem(Keys.groupPk(groupId), Keys.memberSk(self)),
                                    QUERY_CONCURRENCY)
                            .thenMany(Flux.fromIterable(sks))
                            .flatMap(sk -> deleteItem(Keys.userPk(userId), sk), QUERY_CONCURRENCY)
                            .then();
                });
    }

    /**
     * {@link #saveUser} 가 GSI1 의 정렬키에 {@code userName} 을 넣어 두므로 Scan 없이
     * 정확 일치 Query 로 찾을 수 있다. 정렬키는 소문자다({@link Keys#indexKey}) — {@code Kim}
     * 이 있으면 {@code kim} 으로도 찾힌다.
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
                        ":pk", Attrs.s(Keys.USER_INDEX), ":sk", Attrs.s(Keys.indexKey(userName))))
                .build();

        return Paginator.queryAll(client, request).map(item -> Keys.parseUserPk(Attrs.str(item, Keys.PK)));
    }

    /** 직원 META 아이템을 읽는다. GSI1(ALL 프로젝션) 아이템도 같은 속성을 가져 조회 저장소가 함께 쓴다. */
    static DirectoryUser toUser(String userId, Map<String, AttributeValue> item) {
        return new DirectoryUser(
                userId,
                Attrs.str(item, EXTERNAL_ID),
                Attrs.str(item, USER_NAME),
                Attrs.str(item, DISPLAY_NAME),
                Attrs.str(item, EMAIL),
                Attrs.flag(item, ACTIVE),
                new PersonName(
                        Attrs.str(item, NAME_FORMATTED),
                        Attrs.str(item, FAMILY_NAME),
                        Attrs.str(item, GIVEN_NAME),
                        Attrs.str(item, MIDDLE_NAME),
                        Attrs.str(item, HONORIFIC_PREFIX),
                        Attrs.str(item, HONORIFIC_SUFFIX)));
    }

    /** 조직 META 아이템을 읽는다. 조회 저장소가 함께 쓴다. */
    static GroupHeader toGroupHeader(String groupId, Map<String, AttributeValue> item) {
        return new GroupHeader(groupId, Attrs.str(item, EXTERNAL_ID), Attrs.str(item, DISPLAY_NAME));
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
                .map(response -> toGroupHeader(groupId, response.item()));
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

    /** 직원과 같은 규칙으로 쓴다. 조직의 변경은 META 또는 멤버 구성의 변경이다(GSI 설계 §3). */
    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        return findMeta(Keys.groupPk(group.id()))
                .map(this::storedGroup)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(stored -> writeGroup(group, stored.orElse(null)));
    }

    private Mono<Void> writeGroup(DirectoryGroup group, Stored<GroupHeader> stored) {
        GroupHeader header = new GroupHeader(group.id(), group.externalId(), group.displayName());
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

                    // 조직의 변경은 META 의 변경 또는 멤버 구성의 변경이다 — SCIM 의 Group 은 members 를 담는다.
                    // header 를 그대로 비교하지 않고 라운드트립하는 이유는 writeUser 의 자바독과 같다 —
                    // 빈 문자열 displayName 은 저장되지 않아 되읽으면 null 이 된다.
                    boolean 바뀜 = stored == null || !stored.sameAs(toGroupHeader(header.id(), groupMeta(header)))
                            || !떠난멤버.isEmpty() || !새로온멤버.isEmpty();
                    Mono<Void> meta = 바뀜 ? putItem(stamped(groupMeta(header))) : Mono.empty();

                    // 소속 줄이 항상 멤버 줄보다 많거나 같게 유지한다(설계 §5).
                    // 넣을 때는 소속 줄 먼저, 뺄 때는 멤버 줄 먼저 — 중간에 실패해도
                    // "소속 줄만 남는" 안전한 방향으로만 어긋난다. 반대로 어긋나면
                    // 삭제가 그 조직을 못 찾아 권한이 남는다.
                    return Flux.fromIterable(떠난멤버)
                            .map(Keys::parseMemberSk)
                            .flatMap(ref -> deleteItem(Keys.groupPk(group.id()), Keys.memberSk(ref))
                                    .then(deleteItem(Keys.memberPk(ref), Keys.belongsToSk(group.id()))),
                                    QUERY_CONCURRENCY)
                            .then(meta)
                            .then(Flux.fromIterable(새로온멤버)
                                    .flatMap(member -> putItem(belongsToItem(member, group.id()))
                                            .then(putItem(memberItem(group.id(), member))),
                                            QUERY_CONCURRENCY)
                                    .then());
                });
    }

    /** 조직 META 에 쓸 아이템. {@code updatedAt} 은 넣지 않는다. */
    private Map<String, AttributeValue> groupMeta(GroupHeader header) {
        Map<String, AttributeValue> meta = new HashMap<>();
        meta.put(Keys.PK, Attrs.s(Keys.groupPk(header.id())));
        meta.put(Keys.SK, Attrs.s(Keys.META));
        meta.put(Keys.GSI1PK, Attrs.s(Keys.GROUP_INDEX));
        meta.put(Keys.GSI1SK, Attrs.s(Keys.indexKey(presentOr(header.displayName(), header.id()))));
        Attrs.putIfPresent(meta, EXTERNAL_ID, header.externalId());
        Attrs.putIfPresent(meta, DISPLAY_NAME, header.displayName());
        return meta;
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

    /**
     * LDAP 전체 동기화·재적재. 삭제 판단 때문에 원래 GSI1 을 훑던 조회에서 <b>저장본을 함께</b> 받아(GSI1 은 ALL
     * 프로젝션이라 더 읽지 않는다) 비교하고, 새로 생기거나 바뀐 것만 쓴다(GSI 설계 §4).
     *
     * <p><b>순서 — 직원 갱신 → 조직 갱신 → 폐지된 조직 삭제 → 퇴사한 직원 삭제.</b> {@link #writeGroup} 은 "떠난
     * 멤버" 를 그 조직의 현재 멤버 줄(existingMemberSks)과 스냅샷의 목표 멤버를 견주어 스스로 찾아낸다(설계 §3
     * "조직의 변경은 … 멤버 구성의 변경"). 퇴사한 직원을 먼저 지워 버리면 {@link #deleteUser} 가 그 직원이 속한
     * 조직들의 {@code MEMBER#} 줄부터 지우므로, 그 뒤에 도는 {@code writeGroup} 은 이미 멤버 줄이 없는 조직만 보고
     * "떠난 멤버 없음" 으로 읽는다 — META 도 그대로면 조직 자체가 안 바뀐 것처럼 건너뛰어 {@code updatedAt} 도
     * 못 찍고 멤버 구성 변경이 사라진다. 조직을 먼저 갱신하면 {@code writeGroup} 자신이 떠난 멤버를 보고(
     * {@code MEMBER#} 를 지운 다음 {@code BELONGS_TO#} 를 지운다) 변경으로 잡아 낸다. 폐지된 조직·퇴사한 직원의
     * 삭제는 그 뒤에 와도 안전하다 — {@link #deleteGroup}/{@code deleteUser} 는 이미 지워진 줄을 다시 지우려
     * 해도 {@code DeleteItem} 은 없는 키에도 성공한다.
     *
     * <p><b>비교 기준이 최종 일관성 인덱스다.</b> 인덱스가 늦어 생길 수 있는 일은 두 방향이다(GSI 설계 §4).
     *
     * <ul>
     *     <li>"다르다" 로 잘못 보고 한 번 더 쓴다 — 무해하다.</li>
     *     <li>"같다" 로 잘못 보고 안 쓴다 — 이 테이블에 아직 GSI1 로 전파되지 않은 쓰기가 있어야 생긴다. 예전의
     *     무조건 재기록과 달리 <b>이 회차로 낫지 않는다</b> — 그 엔티티가 다음에 다시 바뀔 때까지 낡은 값과
     *     못 찍힌 {@code updatedAt} 이 그대로 남는다.</li>
     * </ul>
     *
     * <p><b>전제 — 이 메서드가 시작할 때 이 테이블에 아직 GSI1 로 전파되지 않은 쓰기가 없어야 한다.</b> 이
     * 메서드에 내용을 넣어 부르는 것은 LDAP 의 전체 동기화(FullSyncUseCase)와 재적재(RebuildUseCase)뿐이고,
     * LDAP 앱에는 그와 동시에 직원·조직을 쓰는 경로가 없어 이전 회차는 오래전에 끝나 있다 — 인덱스는 이미
     * 맞춰져 있다. SCIM 재적재(ScimRebuildUseCase)는 이 메서드를 빈 스냅샷으로만 불러 비교할 것이 없다.
     */
    @Override
    public Mono<Void> replaceWith(DirectorySnapshot snapshot) {
        return Mono.zip(
                        storedIndex(Keys.USER_INDEX, this::storedUser, DirectoryUser::id),
                        storedIndex(Keys.GROUP_INDEX, this::storedGroup, GroupHeader::id))
                .flatMap(stored -> {
                    Map<String, Stored<DirectoryUser>> users = stored.getT1();
                    Map<String, Stored<GroupHeader>> groups = stored.getT2();

                    Mono<Void> upsertUsers = Flux.fromIterable(snapshot.users().values())
                            .flatMap(user -> writeUser(user, users.get(user.id())), QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> upsertGroups = Flux.fromIterable(snapshot.groups().values())
                            .flatMap(group -> writeGroup(group, groups.get(group.id())), QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> removeStaleGroups = Flux.fromIterable(groups.keySet())
                            .filter(id -> !snapshot.groups().containsKey(id))
                            .flatMap(this::deleteGroup, QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> removeStaleUsers = Flux.fromIterable(users.keySet())
                            .filter(id -> !snapshot.users().containsKey(id))
                            .flatMap(this::deleteUser, QUERY_CONCURRENCY)
                            .then();

                    return upsertUsers.then(upsertGroups).then(removeStaleGroups).then(removeStaleUsers);
                });
    }

    /** GSI1 파티션 하나를 훑어 id → 저장본. 삭제 판단과 변경 비교를 한 번의 조회로 한다. */
    private <T> Mono<Map<String, Stored<T>>> storedIndex(String indexPartition,
                                                        Function<Map<String, AttributeValue>, Stored<T>> toStored,
                                                        Function<T, String> idOf) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(indexPartition)))
                .build();
        return Paginator.queryAll(client, request)
                .map(toStored)
                .collectMap(stored -> idOf.apply(stored.value()), stored -> stored);
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

    /**
     * 저장본을 도메인 값과 "그 아이템이 지금 규칙으로 만든 아이템과 같은가" 로 줄여 든다(GSI 설계 §3).
     *
     * <p>아이템 맵을 그대로 들지 않는 이유 — 전체 동기화는 직원 10만 명의 저장본을 한꺼번에 들고 비교하는데,
     * {@code AttributeValue} 맵은 한 건에 1KB 를 넘게 먹는다. "도메인 값이 같고 {@code current}" 는 "updatedAt 을 뺀
     * 아이템 전체가 같다" 와 같은 판단이다 — 키 규칙이 바뀌면 {@code current} 가 거짓이 되어 값이 같아도 다시 쓴다.
     */
    record Stored<T>(T value, boolean current) {

        boolean sameAs(T incoming) {
            return current && value.equals(incoming);
        }
    }

    private Stored<DirectoryUser> storedUser(Map<String, AttributeValue> item) {
        DirectoryUser user = toUser(Keys.parseUserPk(Attrs.str(item, Keys.PK)), item);
        return new Stored<>(user, sameContent(userItem(user), item));
    }

    private Stored<GroupHeader> storedGroup(Map<String, AttributeValue> item) {
        GroupHeader header = toGroupHeader(Keys.parseGroupPk(Attrs.str(item, Keys.PK)), item);
        return new Stored<>(header, sameContent(groupMeta(header), item));
    }

    /** {@link Attrs#putIfPresent} 와 같은 규칙 — null 과 빈 문자열은 "없음" 이다. 키와 속성이 같은 규칙이어야 되읽은 값으로 같은 아이템이 나온다. */
    private static String presentOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** {@code updatedAt} 을 뺀 저장 아이템이 쓰려는 아이템과 같은가. */
    private static boolean sameContent(Map<String, AttributeValue> expected, Map<String, AttributeValue> stored) {
        Map<String, AttributeValue> withoutStamp = new HashMap<>(stored);
        withoutStamp.remove(UPDATED_AT);
        return expected.equals(withoutStamp);
    }

    /** 바뀐 아이템에만 쓰는 시각. 이 값은 이제 "마지막 동기화" 가 아니라 "마지막 변경" 이다. */
    private Map<String, AttributeValue> stamped(Map<String, AttributeValue> item) {
        Map<String, AttributeValue> copy = new HashMap<>(item);
        copy.put(UPDATED_AT, Attrs.s(Instant.now(clock).toString()));
        return copy;
    }

    /** META 한 건을 <b>강한 일관성</b>으로 읽는다. 쓰기 전 비교용이다. */
    private Mono<Map<String, AttributeValue>> findMeta(String pk) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(GetItemResponse::item);
    }

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

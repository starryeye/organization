package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.model.ResourceTimes;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
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
    /** 처음 만든 시각. 처음 쓸 때만 넣고 이후로는 DynamoDB 가 지킨다(if_not_exists, 설계 2026-10-09 §3.1). */
    private static final String CREATED_AT = "createdAt";
    /** 보류 목록 줄의 속성(설계 2026-10-03 §4.1). 키에도 담겨 있지만 줄만 보고 읽을 수 있게 둔다. */
    private static final String CUT_PARENT = "parent";
    private static final String CUT_CHILD = "child";

    /**
     * 직원 META 에서 없을 수 있는 속성 — 이번 값에 없으면 REMOVE 한다(설계 2026-10-09 §3.1). {@link #userItem} 이
     * {@code putIfPresent} 로 넣는 것과 같아야 한다. 빠지면 값을 비워도 옛 값이 남는다 — 테스트("비운 선택 속성은 지워진다")가 지킨다.
     * 아이템에서 속성을 빼더라도 이 목록에서는 빼지 않는다 — UpdateItem 은 목록에 없는 속성을 지우지 않아, 옛 아이템에 남은 속성 때문에
     * {@link #sameContent} 가 늘 "다르다" 가 되고 매 전체 동기화가 다시 쓴다(설계 2026-10-09 §3.1).
     */
    private static final Set<String> USER_OPTIONAL = Set.of(EXTERNAL_ID, USER_NAME, DISPLAY_NAME, Keys.GSI2SK, EMAIL,
            NAME_FORMATTED, FAMILY_NAME, GIVEN_NAME, MIDDLE_NAME, HONORIFIC_PREFIX, HONORIFIC_SUFFIX);

    /** 조직 META 에서 없을 수 있는 속성. {@link #groupMeta} 와 같아야 하고, 빼는 속성도 남겨 둔다({@link #USER_OPTIONAL} 참고). */
    private static final Set<String> GROUP_OPTIONAL = Set.of(EXTERNAL_ID, DISPLAY_NAME);

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
     * 넘겨받은 저장본과 다를 때만 쓰고, 그때만 {@code updatedAt} 을 찍는다(GSI 설계 §3). 같은 값을 다시 쓰면 GSI1(ALL
     * 프로젝션)이 매번 {@code updatedAt} 때문에 다시 쓰여 {@code USER_INDEX} 한 파티션키로 몰렸다.
     *
     * <p>저장본을 다시 읽지 않는다(설계 2026-10-03 §3.5) — 부르는 쪽이 락 안에서 강한 일관성으로 읽었다. {@link #writeUser} 와 같은 이유로
     * 이후 값을 한 번 인코딩했다가 되읽어 비교한다.
     */
    @Override
    public Mono<Void> saveUser(DirectoryUser before, DirectoryUser after) {
        if (before != null && before.equals(toUser(after.id(), userItem(after)))) {
            return Mono.empty();
        }
        return writeMeta(userItem(after), USER_OPTIONAL);
    }

    /**
     * 저장본과 같으면 쓰지 않는다. 다르거나 없으면 {@code updatedAt} 을 찍어 쓴다.
     *
     * <p>{@code user} 를 그대로 비교하지 않고 {@code userItem} 으로 한 번 인코딩했다가 다시
     * {@code toUser} 로 읽어(round-trip) 비교한다 — 빈 문자열은 애초에 속성으로 저장되지
     * 않으므로({@link Attrs#putIfPresent}) 저장본을 되읽으면 {@code null} 이 된다. 들어온
     * {@code user} 가 빈 문자열을 그대로 들고 있으면 라운드트립 없이는 "저장했다면 나왔을 값"과
     * 영원히 달라 보여 매번 다시 쓴다 — 이 태스크가 없애려는 바로 그 재기록이다.
     *
     * <p>쓴 것을 돌려준다(전체 교체의 비교 결과, 설계 2026-10-10 §4.3). 키 규칙만 바뀌어 다시 쓴 것은 값이 같아 돌려주지 않는다 — 이력에 남길 변경이
     * 아니다.
     */
    private Mono<DirectoryChanges.UserDiff> writeUser(DirectoryUser user, Stored<DirectoryUser> stored) {
        DirectoryUser 저장될값 = toUser(user.id(), userItem(user));
        if (stored != null && stored.sameAs(저장될값)) {
            return Mono.empty();
        }
        Mono<Void> 쓰기 = writeMeta(userItem(user), USER_OPTIONAL);
        if (stored != null && stored.value().equals(저장될값)) {
            return 쓰기.then(Mono.empty());
        }
        return 쓰기.thenReturn(new DirectoryChanges.UserDiff(stored == null ? null : stored.value(), 저장될값));
    }

    /** 직원 META 에 쓸 아이템. 두 시각은 넣지 않는다 — 바뀌었을 때만 {@link #writeMeta} 가 넣는다. */
    private Map<String, AttributeValue> userItem(DirectoryUser user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.userPk(user.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.USER_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(Keys.indexKey(presentOr(user.userName(), user.id()))));
        item.put(ACTIVE, Attrs.bool(user.active()));
        Attrs.putIfPresent(item, EXTERNAL_ID, user.externalId());
        Attrs.putIfPresent(item, USER_NAME, user.userName());
        Attrs.putIfPresent(item, DISPLAY_NAME, user.displayName());
        // GSI2(표시명 검색)의 정렬키 — 소문자라 대소문자를 가리지 않는다(Keys.GSI2SK). 표시명이 없거나 비면 쓰지 않는다:
        // 그 직원은 GSI2 에 실리지 않고, DynamoDB 는 인덱스 키에 빈 문자열을 받지 않는다. 조직 META 에는 쓰지 않는다.
        Attrs.putIfPresent(item, Keys.GSI2SK, user.displayName() == null ? null : Keys.indexKey(user.displayName()));
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
        return 직원을_지운다(userId).then();
    }

    /**
     * {@link #deleteUser} 의 몸통 — 멤버 줄을 <b>실제로 지운</b> 조직 id 를 준다. 소속 줄만 남고 멤버 줄은 이미 없던 조직은 넣지 않는다(DeleteItem 의
     * {@code ALL_OLD} 로 안다). 전체 교체가 그 조직의 멤버 변경으로 돌려준다(설계 2026-10-10 §4.3).
     */
    private Mono<List<String>> 직원을_지운다(String userId) {
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
                            .flatMap(groupId -> 지웠으면_준다(Keys.groupPk(groupId), Keys.memberSk(self), groupId),
                                    QUERY_CONCURRENCY)
                            .collectList()
                            .flatMap(멤버였던_조직 -> Flux.fromIterable(sks)
                                    .flatMap(sk -> deleteItem(Keys.userPk(userId), sk), QUERY_CONCURRENCY)
                                    .then(Mono.just(멤버였던_조직)));
                });
    }

    /** 아이템을 지우고, 실제로 있던 것이면 {@code value} 를 준다. */
    private <T> Mono<T> 지웠으면_준다(String pk, String sk, T value) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                        .returnValues(ReturnValue.ALL_OLD)
                        .build()))
                .filter(response -> !response.attributes().isEmpty())
                .map(response -> value);
    }

    @Override
    public Flux<String> findUserIdsByExternalId(String externalId) {
        return pksByExternalId(externalId, Keys.USER_PREFIX).map(Keys::parseUserPk);
    }

    @Override
    public Flux<String> findGroupIdsByExternalId(String externalId) {
        return pksByExternalId(externalId, Keys.GROUP_PREFIX).map(Keys::parseGroupPk);
    }

    /** GSI3 에서 {@code externalId} 가 같은 아이템 중 {@code prefix} 종류의 META 만 골라 PK 를 준다. */
    private Flux<String> pksByExternalId(String externalId, String prefix) {
        if (externalId == null || externalId.isEmpty()) {
            return Flux.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI3)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI3PK, "#sk", Keys.GSI3SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(externalId), ":prefix", Attrs.s(prefix)))
                .build();
        return Paginator.queryAll(client, request)
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .map(item -> Attrs.str(item, Keys.PK));
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

    /** META(또는 GSI1 ALL 프로젝션) 아이템의 두 시각. 없으면 null(설계 2026-10-09 §3.3). 조회 저장소가 쓴다. */
    static ResourceTimes timesOf(Map<String, AttributeValue> item) {
        return new ResourceTimes(Attrs.instant(item, CREATED_AT), Attrs.instant(item, UPDATED_AT));
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
        return storedGroupOf(group.id()).flatMap(stored -> writeGroup(group, stored.orElse(null))).then();
    }

    /**
     * 지금 멤버와 비교하지 않는다 — 부르는 쪽이 락 안에서 {@link #findMembers} 로 확인한 차이다(조직 멤버 PATCH 설계 §6). META 도 다시 읽지 않고
     * 넘겨받은 헤더와 비교한다(설계 2026-10-03 §3.5).
     */
    @Override
    public Mono<Void> saveGroupChange(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed) {
        Stored<GroupHeader> stored = before == null ? null : new Stored<>(before, true);
        return writeMembership(after, stored, List.copyOf(added), List.copyOf(removed));
    }

    private Mono<Optional<Stored<GroupHeader>>> storedGroupOf(String groupId) {
        return findMeta(Keys.groupPk(groupId))
                .map(this::storedGroup)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /** 이름표와 멤버 줄을 맞추고, 바뀌었으면 쓴 것을 돌려준다(설계 2026-10-10 §4.3). 키 규칙만 바뀐 이름표는 돌려주지 않는다. */
    private Mono<DirectoryChanges.GroupDiff> writeGroup(DirectoryGroup group, Stored<GroupHeader> stored) {
        GroupHeader header = new GroupHeader(group.id(), group.externalId(), group.displayName());
        Set<String> targetSks = group.members().stream().map(Keys::memberSk).collect(Collectors.toSet());

        return existingMemberSks(group.id())
                .collectList()
                .flatMap(existing -> {
                    Set<String> existingSks = Set.copyOf(existing);
                    List<MemberRef> 떠난멤버 = existing.stream()
                            .filter(sk -> !targetSks.contains(sk))
                            .map(Keys::parseMemberSk)
                            .toList();
                    // 이미 있는 멤버는 건드리지 않는다. 다시 put 하면 addedAt 이 덮여
                    // "최초 합류" 가 아니라 "마지막 전체 동기화" 를 뜻하게 된다.
                    // 나머지 속성(GSI 키)은 groupId·member 로만 정해져 바뀔 것이 없다.
                    List<MemberRef> 새로온멤버 = group.members().stream()
                            .filter(member -> !existingSks.contains(Keys.memberSk(member)))
                            .toList();
                    GroupHeader 저장될값 = toGroupHeader(header.id(), groupMeta(header));
                    boolean 이름표가_바뀜 = stored == null || !stored.value().equals(저장될값);
                    Mono<Void> 쓰기 = writeMembership(header, stored, 새로온멤버, 떠난멤버);
                    if (!이름표가_바뀜 && 새로온멤버.isEmpty() && 떠난멤버.isEmpty()) {
                        return 쓰기.then(Mono.<DirectoryChanges.GroupDiff>empty());
                    }
                    return 쓰기.thenReturn(new DirectoryChanges.GroupDiff(stored == null ? null : stored.value(), 저장될값,
                            Set.copyOf(새로온멤버), Set.copyOf(떠난멤버)));
                });
    }

    /**
     * META 와 멤버 줄을 쓴다 — {@link #saveGroup} 과 {@link #saveGroupChange} 가 같은 규칙을 쓰도록 한 곳에 둔다.
     *
     * <p>조직의 변경은 META 의 변경 또는 멤버 구성의 변경이다 — SCIM 의 Group 은 members 를 담는다. header 를 그대로 비교하지 않고
     * 라운드트립하는 이유는 writeUser 의 자바독과 같다 — 빈 문자열 displayName 은 저장되지 않아 되읽으면 null 이 된다.
     *
     * <p>소속 줄이 항상 멤버 줄보다 많거나 같게 유지한다(설계 §5). 넣을 때는 소속 줄 먼저, 뺄 때는 멤버 줄 먼저 — 중간에 실패해도
     * "소속 줄만 남는" 안전한 방향으로만 어긋난다. 반대로 어긋나면 삭제가 그 조직을 못 찾아 권한이 남는다.
     */
    private Mono<Void> writeMembership(GroupHeader header, Stored<GroupHeader> stored,
                                       List<MemberRef> 새로온멤버, List<MemberRef> 떠난멤버) {
        boolean 바뀜 = stored == null || !stored.sameAs(toGroupHeader(header.id(), groupMeta(header)))
                || !떠난멤버.isEmpty() || !새로온멤버.isEmpty();
        Mono<Void> meta = 바뀜 ? writeMeta(groupMeta(header), GROUP_OPTIONAL) : Mono.empty();

        return Flux.fromIterable(떠난멤버)
                .flatMap(ref -> deleteItem(Keys.groupPk(header.id()), Keys.memberSk(ref))
                        .then(deleteItem(Keys.memberPk(ref), Keys.belongsToSk(header.id()))),
                        QUERY_CONCURRENCY)
                .then(meta)
                .then(Flux.fromIterable(새로온멤버)
                        .flatMap(member -> putItem(belongsToItem(member, header.id()))
                                .then(putItem(memberItem(header.id(), member))),
                                QUERY_CONCURRENCY)
                        .then());
    }

    /** 직원 META 키를 {@link BatchRequests} 로 묶어 강한 일관성으로 읽는다(설계 2026-10-03 §3.1). */
    @Override
    public Flux<DirectoryUser> findUsers(Set<String> userIds) {
        return Flux.fromIterable(userIds)
                .map(id -> Map.of(Keys.PK, Attrs.s(Keys.userPk(id)), Keys.SK, Attrs.s(Keys.META)))
                .buffer(BatchRequests.GET_LIMIT)
                .flatMap(this::batchGet, QUERY_CONCURRENCY)
                .map(item -> toUser(Keys.parseUserPk(Attrs.str(item, Keys.PK)), item));
    }

    /** 아이디마다 조직 META·직원 META 키를 함께 묻는다 — 키가 아이디의 두 배라 100개 묶음에 50명씩 든다(설계 2026-10-03 §3.1). */
    @Override
    public Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids) {
        return Flux.fromIterable(ids)
                .flatMapIterable(id -> List.of(
                        Map.of(Keys.PK, Attrs.s(Keys.groupPk(id)), Keys.SK, Attrs.s(Keys.META)),
                        Map.of(Keys.PK, Attrs.s(Keys.userPk(id)), Keys.SK, Attrs.s(Keys.META))))
                .buffer(BatchRequests.GET_LIMIT)
                .flatMap(this::batchGet, QUERY_CONCURRENCY)
                .map(item -> Attrs.str(item, Keys.PK))
                .collect(HashMap<String, MemberType>::new, (found, pk) -> {
                    if (pk.startsWith(Keys.GROUP_PREFIX)) {
                        found.put(Keys.parseGroupPk(pk), MemberType.GROUP);
                    } else {
                        found.putIfAbsent(Keys.parseUserPk(pk), MemberType.USER);
                    }
                })
                .map(Map::copyOf);
    }

    /**
     * 멤버 줄 키를 {@code BatchGetItem} 으로 <b>강한 일관성</b>으로 읽는다. 조직 파티션을 훑지 않으므로 읽는 양이 조직 크기가 아니라
     * 후보 수를 따른다(조직 멤버 PATCH 설계 §6). 미처리 키는 {@link BatchRequests} 규칙(5번까지, 백오프)으로 다시 읽는다.
     */
    @Override
    public Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates) {
        return Flux.fromIterable(candidates)
                .buffer(BatchRequests.GET_LIMIT)
                .concatMap(chunk -> batchGet(chunk.stream()
                        .map(ref -> Map.of(
                                Keys.PK, Attrs.s(Keys.groupPk(groupId)),
                                Keys.SK, Attrs.s(Keys.memberSk(ref))))
                        .toList()))
                .map(item -> Keys.parseMemberSk(Attrs.str(item, Keys.SK)))
                .collect(LinkedHashSet<MemberRef>::new, Set::add)
                .map(found -> (Set<MemberRef>) found);
    }

    /** 멤버 줄만, <b>정렬키만</b> 읽는다. META 와 이 조직 자신의 소속 줄({@code BELONGS_TO#})은 접두가 달라 섞이지 않는다. */
    @Override
    public Flux<MemberRef> findMemberRefs(String groupId) {
        return querySortKeys(Keys.groupPk(groupId), Keys.MEMBER_PREFIX).map(Keys::parseMemberSk);
    }

    private Flux<Map<String, AttributeValue>> batchGet(List<Map<String, AttributeValue>> keys) {
        return new BatchRequests(client, properties.getTableName()).get(keys);
    }

    /** 파티션에서 정렬키가 {@code prefix} 로 시작하는 줄의 <b>정렬키만</b> 강한 일관성으로 읽는다. */
    private Flux<String> querySortKeys(String pk, String prefix) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk), ":prefix", Attrs.s(prefix)))
                .projectionExpression("#sk")
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request).map(item -> Attrs.str(item, Keys.SK));
    }

    /** 조직 META 에 쓸 아이템. 두 시각은 넣지 않는다 — {@link #writeMeta} 가 넣는다. */
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
     * 이 조직의 소속 줄만 읽어 상위 조직을 알아낸다(줄 몇 개) — 멤버 줄 10만 개를 다시 훑지 않는다. 지우기는 25개씩 묶어
     * {@link BatchRequests} 로 보낸다(멤버 10만 명이면 약 8,000번, 묶음 재시도 상한). 지우기 요청은 보낼 차례에 만든다 — 10만 명이면
     * 20만 개를 미리 쥐지 않는다.
     */
    @Override
    public Mono<Void> deleteGroup(String groupId, Set<MemberRef> members) {
        String pk = Keys.groupPk(groupId);
        MemberRef 이조직 = MemberRef.group(groupId);
        return querySortKeys(pk, Keys.BELONGS_TO_PREFIX)
                .collectList()
                .flatMap(소속_정렬키 -> {
                    Flux<WriteRequest> 멤버_줄 = Flux.concat(
                            Flux.fromIterable(소속_정렬키)
                                    .map(sk -> 지우기(Keys.groupPk(Keys.parseBelongsToSk(sk)), Keys.memberSk(이조직))),
                            Flux.fromIterable(members).map(member -> 지우기(pk, Keys.memberSk(member))));
                    Flux<WriteRequest> 소속_줄 = Flux.concat(
                            Flux.fromIterable(소속_정렬키).map(sk -> 지우기(pk, sk)),
                            Flux.fromIterable(members).map(member -> 지우기(Keys.memberPk(member), Keys.belongsToSk(groupId))));

                    // 단계 순서: 멤버 줄 → 상위 조직의 변경 시각 → 소속 줄 → META 맨 마지막. 상위 조직은 멤버(이 조직)가 빠졌으니
                    // 바뀐 것이다(SCIM Group 은 members 를 담는다, 설계 2026-10-09 §3.2)
                    Flux<Void> 상위_조직 = Flux.fromIterable(소속_정렬키)
                            .flatMap(sk -> 변경_시각을_올린다(Keys.parseBelongsToSk(sk)), QUERY_CONCURRENCY);
                    return Flux.concat(묶어_보낸다(멤버_줄), 상위_조직, 묶어_보낸다(소속_줄), deleteItem(pk, Keys.META)).then();
                });
    }

    /**
     * 조직의 변경 시각만 올린다 — 멤버(지운 하위 조직)가 빠졌다(설계 2026-10-09 §3.2). META 가 없으면(이미 지워진 조직) 만들지 않는다 —
     * UpdateItem 은 없는 아이템을 만들기 때문에 조건을 단다. 조건이 깨지면 그 조직은 이미 없으니 넘어간다.
     */
    private Mono<Void> 변경_시각을_올린다(String groupId) {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.groupPk(groupId)), Keys.SK, Attrs.s(Keys.META)))
                        .updateExpression("SET #updatedAt = :now")
                        .conditionExpression("attribute_exists(#pk)")
                        .expressionAttributeNames(Map.of("#updatedAt", UPDATED_AT, "#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":now", Attrs.s(Instant.now(clock).toString())))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, 없는_조직 -> Mono.empty());
    }

    private static WriteRequest 지우기(String pk, String sk) {
        return WriteRequest.builder()
                .deleteRequest(DeleteRequest.builder()
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                        .build())
                .build();
    }

    private Mono<Void> 묶어_보낸다(Flux<WriteRequest> requests) {
        BatchRequests 묶음 = new BatchRequests(client, properties.getTableName());
        return requests
                .buffer(BatchRequests.WRITE_LIMIT)
                .flatMap(묶음::write, QUERY_CONCURRENCY)
                .then();
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
        return querySortKeys(Keys.groupPk(groupId), Keys.MEMBER_PREFIX);
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
     *
     * <p><b>실제로 쓴 것을 돌려준다(설계 2026-10-10 §4.3).</b> 비교는 이미 손에 든 저장본({@link Stored})과 하므로 새로 읽는 것이 없다.
     *
     * <p><b>변경마다 알린다(설계 2026-10-15 §4).</b> 직원·조직 하나의 저장·삭제가 끝날 때마다 그 비교 결과로 {@code 변경마다} 를 부른다 — 도중에 실패해도
     * 끝난 변경은 알린 뒤다. 단계 안에서는 동시에 쓰므로 여러 스레드에서 부른다. 직원·조직 하나의 저장이 도중에 실패하면 이미 쓴 그 일부는 알리지 않는다.
     * 한 변경이 실패해 같은 단계의 다른 쓰기가 취소되면, 이미 반영된 그 쓰기도 알리지 않을 수 있다(§8).
     */
    @Override
    public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다) {
        return Mono.zip(
                        storedIndex(Keys.USER_INDEX, this::storedUser, DirectoryUser::id),
                        storedIndex(Keys.GROUP_INDEX, this::storedGroup, GroupHeader::id))
                .flatMap(stored -> {
                    Map<String, Stored<DirectoryUser>> users = stored.getT1();
                    Map<String, Stored<GroupHeader>> groups = stored.getT2();
                    Queue<DirectoryChanges.UserDiff> 직원변경 = new ConcurrentLinkedQueue<>();
                    Queue<DirectoryChanges.GroupDiff> 조직변경 = new ConcurrentLinkedQueue<>();
                    // 끝난 변경은 곧바로 알리고, 돌려줄 결과에도 담는다
                    Consumer<DirectoryChanges.UserDiff> 직원이_끝났다 = diff -> {
                        직원변경.add(diff);
                        변경마다.user(diff);
                    };
                    Consumer<DirectoryChanges.GroupDiff> 조직이_끝났다 = diff -> {
                        조직변경.add(diff);
                        변경마다.group(diff);
                    };

                    Mono<Void> upsertUsers = Flux.fromIterable(snapshot.users().values())
                            .flatMap(user -> writeUser(user, users.get(user.id())), QUERY_CONCURRENCY)
                            .doOnNext(직원이_끝났다)
                            .then();
                    Mono<Void> upsertGroups = Flux.fromIterable(snapshot.groups().values())
                            .flatMap(group -> writeGroup(group, groups.get(group.id())), QUERY_CONCURRENCY)
                            .doOnNext(조직이_끝났다)
                            .then();
                    // 두 단계다 — 사라지는 조직 전부의 멤버를 먼저 읽고, 그다음 지운다. 하위 조직을 지우면 상위 조직의 멤버 줄(그 하위 조직)도
                    // 지우므로, 읽기와 지우기가 섞이면 상위 조직의 나간 멤버에서 하위 조직이 빠진다(비교 결과 = 실제로 쓴 것, 설계 2026-10-10 §9)
                    Mono<Void> removeStaleGroups = Flux.fromIterable(groups.values())
                            .filter(group -> !snapshot.groups().containsKey(group.value().id()))
                            .flatMap(group -> findMemberRefs(group.value().id()).collect(Collectors.toSet())
                                    .map(members -> new DirectoryChanges.GroupDiff(group.value(), null, Set.of(), members)),
                                    QUERY_CONCURRENCY)
                            .collectList()
                            .flatMapMany(Flux::fromIterable)
                            .flatMap(diff -> deleteGroup(diff.id(), diff.removed())
                                    .doOnSuccess(ignored -> 조직이_끝났다.accept(diff)), QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> removeStaleUsers = Flux.fromIterable(users.values())
                            .filter(user -> !snapshot.users().containsKey(user.value().id()))
                            .flatMap(user -> 직원을_지운다(user.value().id())
                                    .doOnNext(멤버였던_조직 -> {
                                        직원이_끝났다.accept(new DirectoryChanges.UserDiff(user.value(), null));
                                        멤버였던_조직.forEach(groupId -> 조직이_끝났다.accept(
                                                멤버만_빠짐(groupId, user.value().id(), snapshot, groups)));
                                    }), QUERY_CONCURRENCY)
                            .then();

                    return upsertUsers.then(upsertGroups).then(removeStaleGroups).then(removeStaleUsers)
                            .then(Mono.fromSupplier(() -> new DirectoryChanges(List.copyOf(직원변경), List.copyOf(조직변경))));
                });
    }

    /**
     * 전체 교체가 직원을 지우며 남아 있던 멤버 줄도 지운 조직 — 조직도가 그 직원을 아직 멤버로 적었지만 직원 목록에는 없는 경우다. 이름표는 그대로다.
     * 이름표는 이번 조직도의 것을, 없으면 저장본의 것을 쓴다.
     */
    private DirectoryChanges.GroupDiff 멤버만_빠짐(String groupId, String userId, DirectorySnapshot snapshot,
                                                Map<String, Stored<GroupHeader>> groups) {
        DirectoryGroup 목표 = snapshot.groups().get(groupId);
        GroupHeader header = 목표 != null
                ? toGroupHeader(groupId, groupMeta(new GroupHeader(목표.id(), 목표.externalId(), 목표.displayName())))
                : groups.containsKey(groupId) ? groups.get(groupId).value() : new GroupHeader(groupId, null, null);
        return new DirectoryChanges.GroupDiff(header, header, Set.of(), Set.of(MemberRef.user(userId)));
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

    /**
     * 직원은 GSI1 로 아이디를 훑은 뒤 {@link #findUsers}(BatchGet 100개씩, 강한 일관성)로 읽는다 — 직원마다 GetItem 을 하면 10만 명에
     * 10만 번 왕복이었다(설계 2026-10-09 §4). 훑은 뒤 읽기 전에 지워진 직원은 결과에 없다. 조직은 멤버 목록이 필요해 조직마다 파티션을 읽는다.
     */
    @Override
    public Mono<DirectorySnapshot> loadAll() {
        Mono<Map<String, DirectoryUser>> users = enumerateIds(Keys.USER_INDEX, Keys::parseUserPk)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .flatMapMany(this::findUsers)
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

    // ---------- 보류 목록 ----------

    /** 보류 목록 파티션 한 곳의 정렬키만 읽는다 — 보통 비어 있어 읽기 1번이다. */
    @Override
    public Flux<GroupEdge> findCutEdges() {
        return querySortKeys(Keys.CYCLE_CUT_PK, Keys.CUT_EDGE_PREFIX).map(Keys::parseCutEdgeSk);
    }

    @Override
    public Mono<Void> changeCutEdges(Set<GroupEdge> added, Set<GroupEdge> removed) {
        return Flux.fromIterable(added)
                .flatMap(edge -> putItem(cutEdgeItem(edge)), QUERY_CONCURRENCY)
                .thenMany(Flux.fromIterable(removed)
                        .flatMap(edge -> deleteItem(Keys.CYCLE_CUT_PK, Keys.cutEdgeSk(edge)), QUERY_CONCURRENCY))
                .then();
    }

    /**
     * 지금 목록과 비교해 달라진 줄만 넣고 뺀다. {@link #replaceWith} 가 GSI1 로 직원·조직만 열거해 보류 줄을 건드리지 않으므로, 재적재는 이 메서드를
     * 따로 부른다.
     */
    @Override
    public Mono<Void> replaceCutEdges(Set<GroupEdge> edges) {
        return findCutEdges().collect(Collectors.toSet()).flatMap(지금 -> {
            Set<GroupEdge> 뺄것 = new HashSet<>(지금);
            뺄것.removeAll(edges);
            Set<GroupEdge> 넣을것 = new HashSet<>(edges);
            넣을것.removeAll(지금);
            return changeCutEdges(넣을것, 뺄것);
        });
    }

    private Map<String, AttributeValue> cutEdgeItem(GroupEdge edge) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.CYCLE_CUT_PK));
        item.put(Keys.SK, Attrs.s(Keys.cutEdgeSk(edge)));
        item.put(CUT_PARENT, Attrs.s(edge.parent()));
        item.put(CUT_CHILD, Attrs.s(edge.child()));
        return item;
    }

    // ---------- 유일 값 표지판 ----------

    @Override
    public Mono<UniqueClaim> claimUnique(UniqueKind kind, String key, String ownerId, Instant at) {
        return 표지판().claim(kind, key, ownerId, at);
    }

    @Override
    public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
        return 표지판().takeOver(kind, key, ownerId, previousOwnerId, at);
    }

    @Override
    public Mono<Void> releaseUnique(UniqueKind kind, String key, String ownerId) {
        return 표지판().release(kind, key, ownerId);
    }

    @Override
    public Mono<Void> clearUniqueMarkers() {
        return 표지판().clear();
    }

    @Override
    public Flux<String> findUniqueOwners(UniqueKind kind) {
        return 표지판().owners(kind);
    }

    private DynamoDbUniqueMarkers 표지판() {
        return new DynamoDbUniqueMarkers(client, properties);
    }

    // ---------- 공통 ----------

    /**
     * 저장본을 도메인 값과 "그 아이템이 지금 규칙으로 만든 아이템과 같은가" 로 줄여 든다(GSI 설계 §3).
     *
     * <p>아이템 맵을 그대로 들지 않는 이유 — 전체 동기화는 직원 10만 명의 저장본을 한꺼번에 들고 비교하는데,
     * {@code AttributeValue} 맵은 한 건에 1KB 를 넘게 먹는다. "도메인 값이 같고 {@code current}" 는 "updatedAt 을 뺀
     * 아이템 전체가 같다" 와 같은 판단이다 — 키 규칙이 바뀌면 {@code current} 가 거짓이 되어 값이 같아도 다시 쓴다.
     * 두 시각({@code updatedAt}·{@code createdAt})은 비교에서 뺀다. META 를 UpdateItem 으로 쓰므로, 모델에서 뺀 속성은 선택 속성 목록에 남겨야
     * 옛 아이템에서 지워진다 — 아니면 그 아이템은 늘 "다르다" 로 보인다.
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

    /**
     * 두 시각({@code updatedAt}·{@code createdAt})을 뺀 저장 아이템이 쓰려는 아이템과 같은가. 둘 다 빼야 한다 — 하나라도 남기면 모든 저장본이
     * 다르게 보여 매 동기화가 전원을 다시 쓴다(GSI 쏠림, 설계 2026-10-09 §3.1).
     */
    private static boolean sameContent(Map<String, AttributeValue> expected, Map<String, AttributeValue> stored) {
        Map<String, AttributeValue> withoutStamps = new HashMap<>(stored);
        withoutStamps.remove(UPDATED_AT);
        withoutStamps.remove(CREATED_AT);
        return expected.equals(withoutStamps);
    }

    /**
     * META 를 쓴다 — PutItem 처럼 통째로 바꾸되 생성 시각은 지킨다(설계 2026-10-09 §3.1).
     *
     * <p>키를 뺀 속성은 모두 SET 하고, {@code optional} 가운데 이번 아이템에 없는 것은 REMOVE 한다 — PutItem 의 "통째 교체" 와 같은 결과다.
     * {@code updatedAt} 은 지금, {@code createdAt} 은 처음 한 번만({@code if_not_exists}) 넣는다. 쓰기 경로는 저장본을 다시 읽지 않으므로
     * (설계 2026-10-03 §3.5) 옛 생성 시각을 손에 쥐고 있지 않다 — 보존을 DynamoDB 에 맡긴다. 처음 만들 때 두 시각은 같은 값이다
     * (RFC 7643 §3.1 MUST). 쓰기 용량·GSI 쓰기는 PutItem 과 같다.
     */
    private Mono<Void> writeMeta(Map<String, AttributeValue> item, Set<String> optional) {
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        List<String> sets = new ArrayList<>();
        for (Map.Entry<String, AttributeValue> attribute : item.entrySet()) {
            if (attribute.getKey().equals(Keys.PK) || attribute.getKey().equals(Keys.SK)) {
                continue;
            }
            String name = "#a" + sets.size();
            String value = ":a" + sets.size();
            names.put(name, attribute.getKey());
            values.put(value, attribute.getValue());
            sets.add(name + " = " + value);
        }
        names.put("#updatedAt", UPDATED_AT);
        names.put("#createdAt", CREATED_AT);
        values.put(":now", Attrs.s(Instant.now(clock).toString()));
        sets.add("#updatedAt = :now");
        sets.add("#createdAt = if_not_exists(#createdAt, :now)");

        List<String> removes = new ArrayList<>();
        for (String attribute : optional) {
            if (!item.containsKey(attribute)) {
                String name = "#r" + removes.size();
                names.put(name, attribute);
                removes.add(name);
            }
        }
        String expression = "SET " + String.join(", ", sets)
                + (removes.isEmpty() ? "" : " REMOVE " + String.join(", ", removes));
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, item.get(Keys.PK), Keys.SK, item.get(Keys.SK)))
                        .updateExpression(expression)
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .build()))
                .then();
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

package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.query.Page;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;

import java.util.Map;
import java.util.function.Function;

/**
 * SCIM 목록·필터 조회의 읽기 (S-1 설계 §5.3).
 *
 * <p>{@code userName}·조직명은 GSI1 의 소문자 정렬키({@link Keys#indexKey})로, {@code externalId} 는 GSI3 로
 * 찾는다. GSI3 는 키만 담으므로 찾은 {@code PK} 로 본 테이블을 강한 일관성으로 다시 읽는다 — 인덱스가 늦어도
 * 낡은 속성을 돌려주지 않는다.
 *
 * <p>목록은 GSI1 파티션({@code USER_INDEX}/{@code GROUP_INDEX})을 정렬키 순서로 {@code limit} 건씩 읽는다.
 * 위치는 {@link Cursor} 로 감싼 LastEvaluatedKey 다. 파티션 전체를 읽는 것은 {@link #countUsers}·
 * {@link #skipUsers} 뿐이고, 그 둘은 책갈피가 없을 때만 불린다(설계 §4.4).
 */
@RequiredArgsConstructor
public class DynamoDbDirectoryQueryRepository implements DirectoryQueryRepository {

    /** 커서에 담는 검색 범위. admin 검색의 커서가 이 목록에 흘러들면 {@link Cursor#decode} 가 거절한다. */
    private static final String USERS_SCOPE = "SCIM/" + Keys.GSI1 + "/" + Keys.USER_INDEX;
    private static final String GROUPS_SCOPE = "SCIM/" + Keys.GSI1 + "/" + Keys.GROUP_INDEX;

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    /** GSI3 로 찾은 키를 본 테이블에서 강한 일관성으로 다시 읽는다. */
    private final DirectoryStateRepository state;

    @Override
    public Flux<DirectoryUser> findUsersByUserName(String userName) {
        return exact(Keys.USER_INDEX, userName).map(DynamoDbDirectoryQueryRepository::user);
    }

    @Override
    public Flux<DirectoryUser> findUsersByExternalId(String externalId) {
        return byExternalId(externalId, Keys.USER_PREFIX)
                .concatMap(pk -> state.findUser(Keys.parseUserPk(pk)));
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByDisplayName(String displayName) {
        return exact(Keys.GROUP_INDEX, displayName).map(DynamoDbDirectoryQueryRepository::group);
    }

    @Override
    public Flux<GroupHeader> findGroupHeadersByExternalId(String externalId) {
        return byExternalId(externalId, Keys.GROUP_PREFIX)
                .concatMap(pk -> state.findGroupHeader(Keys.parseGroupPk(pk)));
    }

    @Override
    public Mono<Page<DirectoryUser>> listUsers(String from, int limit, boolean descending) {
        return page(USERS_SCOPE, Keys.USER_INDEX, from, limit, descending, DynamoDbDirectoryQueryRepository::user);
    }

    @Override
    public Mono<Page<GroupHeader>> listGroupHeaders(String from, int limit, boolean descending) {
        return page(GROUPS_SCOPE, Keys.GROUP_INDEX, from, limit, descending, DynamoDbDirectoryQueryRepository::group);
    }

    @Override
    public Mono<Long> countUsers() {
        return count(Keys.USER_INDEX);
    }

    @Override
    public Mono<Long> countGroups() {
        return count(Keys.GROUP_INDEX);
    }

    @Override
    public Mono<String> skipUsers(long n, boolean descending) {
        return skip(USERS_SCOPE, Keys.USER_INDEX, n, descending);
    }

    @Override
    public Mono<String> skipGroups(long n, boolean descending) {
        return skip(GROUPS_SCOPE, Keys.GROUP_INDEX, n, descending);
    }

    private static DirectoryUser user(Map<String, AttributeValue> item) {
        return DynamoDbDirectoryStateRepository.toUser(Keys.parseUserPk(Attrs.str(item, Keys.PK)), item);
    }

    private static GroupHeader group(Map<String, AttributeValue> item) {
        return DynamoDbDirectoryStateRepository.toGroupHeader(Keys.parseGroupPk(Attrs.str(item, Keys.PK)), item);
    }

    /** GSI1 정렬키가 값의 소문자와 같은 아이템. 대소문자만 다른 둘이 있으면 둘 다 돌려준다. */
    private Flux<Map<String, AttributeValue>> exact(String partition, String value) {
        if (value == null || value.isEmpty()) {
            return Flux.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk AND #sk = :sk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK, "#sk", Keys.GSI1SK))
                .expressionAttributeValues(Map.of(
                        ":pk", Attrs.s(partition), ":sk", Attrs.s(Keys.indexKey(value))))
                .build();
        return Paginator.queryAll(client, request);
    }

    /** GSI3 에서 {@code externalId} 가 같은 아이템 중 {@code prefix} 종류의 META 만 골라 PK 를 준다. */
    private Flux<String> byExternalId(String externalId, String prefix) {
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

    /**
     * {@code Mono.defer} 인 이유는 admin 검색과 같다 — {@link Cursor#decode} 가 손상된 위치에서 던지는 예외를
     * 조립 시점이 아니라 구독 시점의 {@code onError} 로 만든다.
     */
    private <T> Mono<Page<T>> page(String scope, String partition, String from, int limit, boolean descending,
                                   Function<Map<String, AttributeValue>, T> mapper) {
        return Mono.defer(() -> {
            QueryRequest.Builder request = partitionQuery(partition, descending).limit(limit);
            Map<String, AttributeValue> start = Cursor.decode(scope, from);
            if (start != null) {
                request.exclusiveStartKey(start);
            }
            return Mono.fromFuture(() -> client.query(request.build()))
                    .map(response -> new Page<>(
                            response.items().stream().map(mapper).toList(),
                            Cursor.encode(scope, response.lastEvaluatedKey())));
        });
    }

    private QueryRequest.Builder partitionQuery(String partition, boolean descending) {
        return QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(partition)))
                .scanIndexForward(!descending);
    }

    /** 파티션을 한 번 훑어 센다. 1MB 마다 끊기므로 LastEvaluatedKey 를 끝까지 따라간다. */
    private Mono<Long> count(String partition) {
        QueryRequest request = partitionQuery(partition, false).select(Select.COUNT).build();
        return Mono.fromFuture(() -> client.query(request))
                .expand(response -> {
                    Map<String, AttributeValue> lastKey = response.lastEvaluatedKey();
                    if (lastKey == null || lastKey.isEmpty()) {
                        return Mono.empty();
                    }
                    QueryRequest next = request.toBuilder().exclusiveStartKey(lastKey).build();
                    return Mono.fromFuture(() -> client.query(next));
                })
                .map(QueryResponse::count)
                .reduce(0L, (sum, count) -> sum + count);
    }

    /**
     * 앞의 {@code n} 건을 키만 읽으며 건너뛰고, 마지막으로 건너뛴 아이템의 키를 위치로 준다. 그 키가 곧 GSI1
     * 질의의 LastEvaluatedKey 모양({@code PK, SK, GSI1PK, GSI1SK})이다. 책갈피가 없을 때만 불린다.
     */
    private Mono<String> skip(String scope, String partition, long n, boolean descending) {
        if (n <= 0) {
            return Mono.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#gpk = :pk")
                .projectionExpression("#pk, #sk, #gpk, #gsk")
                .expressionAttributeNames(Map.of(
                        "#pk", Keys.PK, "#sk", Keys.SK, "#gpk", Keys.GSI1PK, "#gsk", Keys.GSI1SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(partition)))
                .scanIndexForward(!descending)
                .build();
        return Paginator.queryAll(client, request)
                .take(n)
                .reduce((previous, current) -> current)
                .map(last -> Cursor.encode(scope, last));
    }
}

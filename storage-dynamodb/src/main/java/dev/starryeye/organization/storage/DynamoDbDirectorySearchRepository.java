package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * GSI 파티션 안에서 정렬키 접두사로 훑는다.
 *
 * <p>DynamoDB 정렬키로 할 수 있는 것은 정확 일치와 {@code begins_with} 뿐이다.
 * 부분일치는 Scan 이거나 검색엔진이므로 이 계획의 범위 밖이다(설계 §12).
 */
@RequiredArgsConstructor
public class DynamoDbDirectorySearchRepository implements DirectorySearchRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    @Override
    public Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit) {
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.USER_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }

    @Override
    public Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit) {
        // 파티션이 USER_INDEX 인 것은 오타가 아니다 — GSI2 는 GSI1 과 같은 파티션키 속성을
        // 쓰고 정렬키만 displayName 으로 바꾼 인덱스다(Keys.GSI2PK 참고). 그래서 이 질의는
        // 조직 META(GROUP_INDEX)를 건드리지 않고 직원만 본다.
        return query(Keys.GSI2, Keys.GSI2PK, Keys.GSI2SK, Keys.USER_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }

    @Override
    public Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit) {
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.GROUP_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toGroupSummary);
    }

    /**
     * 조직 META 아이템 하나만 집어 온다. {@code Query} 가 아니라 {@code GetItem} 인 것이
     * 핵심이다 — {@code Query(PK=GROUP#code)} 는 같은 파티션의 멤버십 아이템까지 전부
     * 끌어온다.
     */
    @Override
    public Mono<GroupSummary> findGroupSummary(String orgCode) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(Keys.groupPk(orgCode)), Keys.SK, Attrs.s(Keys.META)))
                .projectionExpression("#pk, #displayName")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#displayName", "displayName"))
                .build();

        return Mono.fromFuture(() -> client.getItem(request))
                .filter(GetItemResponse::hasItem)
                .map(response -> toGroupSummary(response.item()));
    }

    /**
     * {@code Mono.defer} 로 감싸는 이유: {@link Cursor#decode} 는 손상된 커서에서
     * {@link IllegalArgumentException} 을 던지는데, 감싸지 않으면 이 예외가 Mono 를
     * 조립하는 시점(메서드 호출 시점)에 곧바로 튀어나온다. 그러면 {@code Mono.zip} 처럼
     * 여러 Mono 를 조립만 하고 아직 구독하지 않은 코드에서 인자 평가 중에 예외가 터져
     * Reactor 체인에 진입하지도 못한 채 죽는다. {@code defer} 로 감싸면 구독 시점까지
     * 평가가 미뤄져 예외가 정상적인 {@code onError} 신호가 된다.
     */
    private <T> Mono<Page<T>> query(String indexName, String pkName, String skName, String partition,
                                    String prefix, String cursor, int limit,
                                    Function<Map<String, AttributeValue>, T> mapper) {
        return Mono.defer(() -> {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(properties.getTableName())
                    .indexName(indexName)
                    .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                    .expressionAttributeNames(Map.of("#pk", pkName, "#sk", skName))
                    .expressionAttributeValues(Map.of(
                            ":pk", Attrs.s(partition), ":prefix", Attrs.s(prefix)))
                    .limit(limit);

            Map<String, AttributeValue> start = Cursor.decode(cursor);
            if (start != null) {
                request.exclusiveStartKey(start);
            }

            return Mono.fromFuture(() -> client.query(request.build()))
                    .map(response -> toPage(response, mapper));
        });
    }

    private <T> Page<T> toPage(QueryResponse response, Function<Map<String, AttributeValue>, T> mapper) {
        List<T> items = response.items().stream().map(mapper).toList();
        return new Page<>(items, Cursor.encode(response.lastEvaluatedKey()));
    }

    private static UserSummary toUserSummary(Map<String, AttributeValue> item) {
        return new UserSummary(
                Keys.parseUserPk(Attrs.str(item, Keys.PK)),
                Attrs.str(item, "userName"),
                Attrs.str(item, "displayName"),
                Attrs.flag(item, "active"));
    }

    private static GroupSummary toGroupSummary(Map<String, AttributeValue> item) {
        return new GroupSummary(
                Keys.parseGroupPk(Attrs.str(item, Keys.PK)),
                Attrs.str(item, "displayName"));
    }
}

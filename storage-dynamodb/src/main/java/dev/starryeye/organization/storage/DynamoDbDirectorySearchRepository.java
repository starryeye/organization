package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
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
        return query(Keys.GSI2, Keys.GSI2PK, Keys.GSI2SK, Keys.USER_DISPLAY_NAME_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }

    @Override
    public Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit) {
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.GROUP_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toGroupSummary);
    }

    private <T> Mono<Page<T>> query(String indexName, String pkName, String skName, String partition,
                                    String prefix, String cursor, int limit,
                                    Function<Map<String, AttributeValue>, T> mapper) {
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

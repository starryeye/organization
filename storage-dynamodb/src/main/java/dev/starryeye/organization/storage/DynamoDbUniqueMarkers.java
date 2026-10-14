package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 유일 값 표지판 줄(설계 2026-10-14 §3). 종류마다 파티션 하나({@link Keys#uniquePk})이고 정렬키가 값의 키다 — 키는 core 가 만든다
 * ({@link UniqueKind#key}). GSI 키 속성을 넣지 않아 어느 인덱스에도 실리지 않는다. 쓰기는 모두 조건부라 락이 겹치는 틈에서도 남의 표지판을
 * 덮어쓰지 않는다. {@link DynamoDbDirectoryStateRepository} 가 이것에 맡긴다.
 */
final class DynamoDbUniqueMarkers {

    private static final String OWNER_ID = "ownerId";
    private static final String CLAIMED_AT = "claimedAt";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    DynamoDbUniqueMarkers(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 없으면 차지한다(빈 값). 있으면 아무것도 쓰지 않고 그 표지판을 돌려준다 — 조건 실패 응답에 실려 온다(ALL_OLD). */
    Mono<UniqueClaim> claim(UniqueKind kind, String key, String ownerId, Instant at) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(properties.getTableName())
                        .item(item(kind, key, ownerId, at))
                        .conditionExpression("attribute_not_exists(#pk)")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build()))
                .then(Mono.<UniqueClaim>empty())
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.just(있던_표지판(error, at)));
    }

    /** 주인이 아직 {@code previousOwnerId} 일 때만 바꾼다. 바꿨으면 참이다. */
    Mono<Boolean> takeOver(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(properties.getTableName())
                        .item(item(kind, key, ownerId, at))
                        .conditionExpression("#owner = :previous")
                        .expressionAttributeNames(Map.of("#owner", OWNER_ID))
                        .expressionAttributeValues(Map.of(":previous", Attrs.s(previousOwnerId)))
                        .build()))
                .thenReturn(true)
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.just(false));
    }

    /** 주인이 {@code ownerId} 일 때만 지운다. 남의 것이거나 없으면 아무것도 하지 않는다. */
    Mono<Void> release(UniqueKind kind, String key, String ownerId) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.uniquePk(kind)), Keys.SK, Attrs.s(key)))
                        .conditionExpression("#owner = :owner")
                        .expressionAttributeNames(Map.of("#owner", OWNER_ID))
                        .expressionAttributeValues(Map.of(":owner", Attrs.s(ownerId)))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }

    /** 두 파티션을 강한 일관성으로 읽어 묶음으로 지운다 — SCIM wipe 가 조직도를 비운 뒤 부른다. */
    Mono<Void> clear() {
        BatchRequests 묶음 = new BatchRequests(client, properties.getTableName());
        return Flux.fromArray(UniqueKind.values())
                .concatMap(kind -> 정렬키들(Keys.uniquePk(kind))
                        .map(sk -> WriteRequest.builder()
                                .deleteRequest(DeleteRequest.builder()
                                        .key(Map.of(Keys.PK, Attrs.s(Keys.uniquePk(kind)), Keys.SK, Attrs.s(sk)))
                                        .build())
                                .build()))
                .buffer(BatchRequests.WRITE_LIMIT)
                .concatMap(묶음::write)
                .then();
    }

    private Flux<String> 정렬키들(String pk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .projectionExpression("#sk")
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request).map(item -> Attrs.str(item, Keys.SK));
    }

    private static Map<String, AttributeValue> item(UniqueKind kind, String key, String ownerId, Instant at) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.uniquePk(kind)));
        item.put(Keys.SK, Attrs.s(key));
        item.put(OWNER_ID, Attrs.s(ownerId));
        item.put(CLAIMED_AT, Attrs.n(at.toEpochMilli()));
        return item;
    }

    /** 조건 실패 응답에 실린 표지판. 돌려받지 못했으면 주인을 모르는 새 표지판으로 본다 — 부르는 쪽이 503 으로 판단한다. */
    private static UniqueClaim 있던_표지판(ConditionalCheckFailedException error, Instant at) {
        if (!error.hasItem() || error.item().get(OWNER_ID) == null || error.item().get(CLAIMED_AT) == null) {
            return new UniqueClaim("", at);
        }
        return new UniqueClaim(Attrs.str(error.item(), OWNER_ID),
                Instant.ofEpochMilli(Attrs.longValue(error.item(), CLAIMED_AT)));
    }
}

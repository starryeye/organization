package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.just(있던_표지판(kind, key, error, at)));
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

    /** 그 종류 파티션의 주인 아이디를 강한 일관성으로 모두 읽는다 — SCIM wipe 가 GSI 열거에서 빠진 직원·조직을 찾는다. 같은 주인이 여러 번 나올 수 있다. */
    Flux<String> owners(UniqueKind kind) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#owner", OWNER_ID))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.uniquePk(kind))))
                .projectionExpression("#owner")
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request).map(item -> Attrs.str(item, OWNER_ID));
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

    /**
     * 조건 실패 응답에 실린 표지판. 돌려받지 못했으면 주인을 모르는 새 표지판으로 본다 — 부르는 쪽이 503 으로 판단한다. 조건이 실패했는데 ALL_OLD 가
     * 없는 것은 있어서는 안 되는 일이라 경고를 남긴다. 값(키)은 로그에 남기지 않는다 — 사용자 이름이다.
     */
    private static UniqueClaim 있던_표지판(UniqueKind kind, String key, ConditionalCheckFailedException error, Instant at) {
        if (!error.hasItem() || error.item().get(OWNER_ID) == null || error.item().get(CLAIMED_AT) == null) {
            log.warn("표지판 조건 실패 응답에 기존 표지판이 없다 — 주인을 몰라 503 으로 본다: kind={}, key 길이={}", kind, key.length());
            return new UniqueClaim("", at);
        }
        return new UniqueClaim(Attrs.str(error.item(), OWNER_ID),
                Instant.ofEpochMilli(Attrs.longValue(error.item(), CLAIMED_AT)));
    }
}

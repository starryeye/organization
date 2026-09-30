package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.DailyJobClaims;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 하루 1회 표지를 "없을 때만 쓰기"로 잡는다(설계 2026-09-30 §5.1). 사흘 뒤 테이블 TTL 로 사라진다 — 날짜가 키에 들어 있어 다음 날은 새 줄이다.
 */
@RequiredArgsConstructor
public class DynamoDbDailyJobClaims implements DailyJobClaims {

    private static final Duration 보관 = Duration.ofDays(3);

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<Boolean> claim(String job, LocalDate day) {
        return Mono.defer(() -> {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.dailyJobPk(job, day)));
            item.put(Keys.SK, Attrs.s(Keys.META));
            item.put(Keys.EXPIRES_AT, Attrs.n(clock.instant().plus(보관).getEpochSecond()));

            return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                            .tableName(properties.getTableName())
                            .item(item)
                            .conditionExpression("attribute_not_exists(#pk)")
                            .expressionAttributeNames(Map.of("#pk", Keys.PK))
                            .build()))
                    .thenReturn(true)
                    .onErrorResume(ConditionalCheckFailedException.class, taken -> Mono.just(false));
        });
    }
}

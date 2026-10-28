package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.DailyJobClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 하루 1회 표지를 조건부 쓰기로 잡고 끝낸다(설계 2026-09-30 §5.1, 2026-10-27 §9). 아이템은 {@code DAILY#<job>#<UTC 날짜>} 한 줄이고 칸은 넷이다.
 * <ul>
 *   <li>{@code state} — {@code CLAIMED}(잡음) 또는 {@code DONE}(완료).</li>
 *   <li>{@code owner} — 잡을 때마다 새로 만드는 UUID. 완료·해제의 조건이다.</li>
 *   <li>{@code leaseUntil} — 임대 끝(epoch 초). 이 시각이 지난 CLAIMED 는 잡은 인스턴스가 죽었다고 보고 다시 잡는다.</li>
 *   <li>{@code expiresAt} — 사흘 뒤 테이블 TTL 로 사라진다. 날짜가 키에 들어 있어 다음 날은 새 줄이다.</li>
 * </ul>
 * 표지를 조건 없이 지우지 않는다 — 임대를 잃은 인스턴스의 늦은 해제가 남이 새로 잡은 표지를 지우면 그날 일이 한 번 더 돈다.
 */
@Slf4j
@RequiredArgsConstructor
public class DynamoDbDailyJobClaims implements DailyJobClaims {

    private static final Duration 보관 = Duration.ofDays(3);
    private static final String STATE = "state";
    private static final String OWNER = "owner";
    private static final String LEASE_UNTIL = "leaseUntil";
    private static final String CLAIMED = "CLAIMED";
    private static final String DONE = "DONE";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    /** 없거나, CLAIMED 인데 임대가 끝났으면 잡는다. DONE 은 조건에 들지 않으므로 그날은 다시 잡지 못한다. 지금은 이 어댑터의 시계다. */
    @Override
    public Mono<Boolean> claim(String job, LocalDate day, String owner, Instant leaseUntil) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.dailyJobPk(job, day)));
            item.put(Keys.SK, Attrs.s(Keys.META));
            item.put(STATE, Attrs.s(CLAIMED));
            item.put(OWNER, Attrs.s(owner));
            item.put(LEASE_UNTIL, Attrs.n(leaseUntil.getEpochSecond()));
            item.put(Keys.EXPIRES_AT, Attrs.n(now.plus(보관).getEpochSecond()));

            return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                            .tableName(properties.getTableName())
                            .item(item)
                            .conditionExpression("attribute_not_exists(#pk) OR (#state = :claimed AND #leaseUntil < :now)")
                            .expressionAttributeNames(Map.of("#pk", Keys.PK, "#state", STATE, "#leaseUntil", LEASE_UNTIL))
                            .expressionAttributeValues(Map.of(
                                    ":claimed", Attrs.s(CLAIMED), ":now", Attrs.n(now.getEpochSecond())))
                            .build()))
                    .thenReturn(true)
                    .onErrorResume(ConditionalCheckFailedException.class, taken -> Mono.just(false));
        });
    }

    /** owner 가 쥔 표지만 DONE 으로 바꾼다. 남의 것이거나 없으면 조건이 깨지고 아무것도 하지 않는다. */
    @Override
    public Mono<Void> complete(String job, LocalDate day, String owner) {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(키(job, day))
                        .updateExpression("SET #state = :done")
                        .conditionExpression("#owner = :owner")
                        .expressionAttributeNames(Map.of("#state", STATE, "#owner", OWNER))
                        .expressionAttributeValues(Map.of(":done", Attrs.s(DONE), ":owner", Attrs.s(owner)))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, error -> {
                    log.info("{}({}) 표지가 없거나 다른 인스턴스의 것이라 완료로 적지 않는다 — 임대를 넘겨 끝났다", job, day);
                    return Mono.empty();
                });
    }

    /** owner 가 쥔 표지만 지운다. 남의 것이거나 없으면 조건이 깨지고 아무것도 하지 않는다. */
    @Override
    public Mono<Void> release(String job, LocalDate day, String owner) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(키(job, day))
                        .conditionExpression("#owner = :owner")
                        .expressionAttributeNames(Map.of("#owner", OWNER))
                        .expressionAttributeValues(Map.of(":owner", Attrs.s(owner)))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, error -> {
                    log.info("{}({}) 표지가 없거나 다른 인스턴스의 것이라 풀지 않는다 — 임대를 넘겨 끝났다", job, day);
                    return Mono.empty();
                });
    }

    private static Map<String, AttributeValue> 키(String job, LocalDate day) {
        return Map.of(Keys.PK, Attrs.s(Keys.dailyJobPk(job, day)), Keys.SK, Attrs.s(Keys.META));
    }
}

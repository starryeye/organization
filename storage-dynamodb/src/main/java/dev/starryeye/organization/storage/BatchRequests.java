package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.TemporaryFailureException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB 묶음 요청(BatchGetItem·BatchWriteItem)을 보내고, 처리 못 한 키를 정해진 횟수만 다시 보낸다(설계 2026-10-02 §4.4).
 *
 * <p>DynamoDB 는 처리량이 모자라면 묶음 일부를 돌려준다(UnprocessedKeys·UnprocessedItems). AWS 는 지수 백오프로 다시 보내라고 권한다.
 * 상한을 두지 않으면 스로틀이 계속될 때 락을 쥔 요청이 끝나지 않는다 — 그래서 {@value #MAX_ATTEMPTS}번까지 {@link #BASE_DELAY} 부터 두 배씩
 * 쉬며 보내고, 그래도 남으면 남은 수를 담아 실패시킨다. 상한을 다 쓰면 일시 장애다 — 처리량이 나아진 뒤 다시 보내면 된다(③-1 이월, 설계 2026-10-05 §3.1).
 */
@RequiredArgsConstructor
final class BatchRequests {

    /** BatchGetItem 한 번에 담을 수 있는 키 수(DynamoDB 한도). */
    static final int GET_LIMIT = 100;
    /** BatchWriteItem 한 번에 담을 수 있는 요청 수(DynamoDB 한도). */
    static final int WRITE_LIMIT = 25;
    static final int MAX_ATTEMPTS = 5;
    static final Duration BASE_DELAY = Duration.ofMillis(100);

    private final DynamoDbAsyncClient client;
    private final String tableName;

    /** 키를 강한 일관성으로 읽는다. 키는 {@value #GET_LIMIT}개 이하. */
    Flux<Map<String, AttributeValue>> get(List<Map<String, AttributeValue>> keys) {
        if (keys.isEmpty()) {
            return Flux.empty();
        }
        return get(KeysAndAttributes.builder().keys(keys).consistentRead(true).build(), 1);
    }

    private Flux<Map<String, AttributeValue>> get(KeysAndAttributes keys, int attempt) {
        return Mono.fromFuture(() -> client.batchGetItem(BatchGetItemRequest.builder()
                        .requestItems(Map.of(tableName, keys))
                        .build()))
                .flatMapMany(response -> {
                    Flux<Map<String, AttributeValue>> 읽은것 =
                            Flux.fromIterable(response.responses().getOrDefault(tableName, List.of()));
                    KeysAndAttributes 남은것 = response.unprocessedKeys().get(tableName);
                    if (남은것 == null || !남은것.hasKeys() || 남은것.keys().isEmpty()) {
                        return 읽은것;
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        return 읽은것.concatWith(Mono.error(new TemporaryFailureException(
                                "BatchGetItem 이 %d회 보낸 뒤에도 %d건을 읽지 못했다".formatted(attempt, 남은것.keys().size()),
                                TemporaryFailureException.기본_대기)));
                    }
                    KeysAndAttributes 다시 = 남은것.toBuilder().consistentRead(true).build();
                    return 읽은것.concatWith(Mono.delay(쉬는시간(attempt)).thenMany(get(다시, attempt + 1)));
                });
    }

    /** 쓰기·지우기 요청을 보낸다. 요청은 {@value #WRITE_LIMIT}개 이하. */
    Mono<Void> write(List<WriteRequest> requests) {
        return write(requests, 1);
    }

    private Mono<Void> write(List<WriteRequest> requests, int attempt) {
        if (requests.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromFuture(() -> client.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(tableName, requests))
                        .build()))
                .flatMap(response -> {
                    List<WriteRequest> 남은것 = response.unprocessedItems().getOrDefault(tableName, List.of());
                    if (남은것.isEmpty()) {
                        return Mono.empty();
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        return Mono.error(new TemporaryFailureException(
                                "BatchWriteItem 이 %d회 보낸 뒤에도 %d건을 처리하지 못했다".formatted(attempt, 남은것.size()),
                                TemporaryFailureException.기본_대기));
                    }
                    return Mono.delay(쉬는시간(attempt)).then(write(남은것, attempt + 1));
                })
                .then();
    }

    private static Duration 쉬는시간(int attempt) {
        return BASE_DELAY.multipliedBy(1L << (attempt - 1));
    }
}

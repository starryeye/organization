package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.TemporaryFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DynamoDB 묶음 요청은 처리 못 한 키를 정해진 횟수만 다시 보낸다 (설계 2026-10-02 §4.4).
 */
class BatchRequestsTest extends DynamoDbTestSupport {

    private static Map<String, AttributeValue> 키(String pk) {
        return Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META));
    }

    private void 심는다(String pk) {
        client.putItem(PutItemRequest.builder().tableName(properties.getTableName()).item(키(pk)).build()).join();
    }

    /** batchGetItem·batchWriteItem 만 가로채고 나머지는 실제 클라이언트로 보낸다. */
    private DynamoDbAsyncClient 가로채는_클라이언트(java.util.function.BiFunction<String, Object, Object> 가로채기) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    Object 바꾼것 = 가로채기.apply(method.getName(), args == null ? null : args[0]);
                    return 바꾼것 != null ? 바꾼것 : method.invoke(client, args);
                });
    }

    @Test
    @DisplayName("처리 못 한 키가 계속 남으면 다섯 번 보낸 뒤 남은 수를 담아 실패한다 — 끝없이 다시 묻지 않는다")
    void 읽기는_다섯번_보낸_뒤_실패한다() {
        // given — 매번 아무것도 읽지 못하고 키를 그대로 돌려준다
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        var batch = new BatchRequests(가로채는_클라이언트((name, request) -> {
            if (!name.equals("batchGetItem")) {
                return null;
            }
            보낸_횟수.incrementAndGet();
            return CompletableFuture.completedFuture(BatchGetItemResponse.builder()
                    .responses(Map.of())
                    .unprocessedKeys(((BatchGetItemRequest) request).requestItems())
                    .build());
        }), properties.getTableName());

        // when, then
        assertThatThrownBy(() -> batch.get(List.of(키("USER#kim"))).collectList().block())
                .isInstanceOfSatisfying(TemporaryFailureException.class,
                        error -> assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(10)))
                .hasMessageContaining("1건");
        assertThat(보낸_횟수).hasValue(BatchRequests.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("한 번 밀린 키는 쉬었다 다시 읽어 전부 돌려준다")
    void 밀린_키를_다시_읽는다() {
        // given — 두 줄이 있고, 첫 요청은 통째로 밀린다
        심는다("USER#kim");
        심는다("USER#lee");
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        var batch = new BatchRequests(가로채는_클라이언트((name, request) -> {
            if (!name.equals("batchGetItem") || 보낸_횟수.incrementAndGet() > 1) {
                return null;
            }
            return CompletableFuture.completedFuture(BatchGetItemResponse.builder()
                    .responses(Map.of())
                    .unprocessedKeys(((BatchGetItemRequest) request).requestItems())
                    .build());
        }), properties.getTableName());

        // when
        var 읽은것 = batch.get(List.of(키("USER#kim"), 키("USER#lee"))).collectList().block();

        // then
        assertThat(읽은것).hasSize(2);
        assertThat(보낸_횟수).hasValue(2);
    }

    @Test
    @DisplayName("쓰기도 처리 못 한 요청이 계속 남으면 다섯 번 보낸 뒤 실패한다")
    void 쓰기는_다섯번_보낸_뒤_실패한다() {
        // given
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        var batch = new BatchRequests(가로채는_클라이언트((name, request) -> {
            if (!name.equals("batchWriteItem")) {
                return null;
            }
            보낸_횟수.incrementAndGet();
            return CompletableFuture.completedFuture(BatchWriteItemResponse.builder()
                    .unprocessedItems(((BatchWriteItemRequest) request).requestItems())
                    .build());
        }), properties.getTableName());
        WriteRequest 하나 = WriteRequest.builder().putRequest(PutRequest.builder().item(키("USER#kim")).build()).build();

        // when, then
        assertThatThrownBy(() -> batch.write(List.of(하나)).block())
                .isInstanceOfSatisfying(TemporaryFailureException.class,
                        error -> assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(10)))
                .hasMessageContaining("1건");
        assertThat(보낸_횟수).hasValue(BatchRequests.MAX_ATTEMPTS);
    }
}

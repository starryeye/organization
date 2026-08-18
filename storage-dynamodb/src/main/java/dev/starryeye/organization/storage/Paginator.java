package dev.starryeye.organization.storage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.Map;

/**
 * DynamoDB 의 Query 는 응답이 1MB 를 넘으면 나머지를 잘라내고 LastEvaluatedKey 를 돌려준다.
 * 이 모듈의 모든 Query 는 반드시 이 값을 따라가야 한다 — 그렇지 않으면 픽스처가 작은
 * 테스트에서는 멀쩡해 보이다가, 운영 데이터에서는 결과가 조용히 잘려 나간다.
 */
final class Paginator {

    private Paginator() {
    }

    /** LastEvaluatedKey 를 따라가며 전체 페이지를 이어붙인다. */
    static Flux<Map<String, AttributeValue>> queryAll(DynamoDbAsyncClient client, QueryRequest request) {
        return Mono.fromFuture(() -> client.query(request))
                .flatMapMany(response -> {
                    Flux<Map<String, AttributeValue>> page = Flux.fromIterable(response.items());
                    if (response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()) {
                        return page;
                    }
                    QueryRequest next = request.toBuilder()
                            .exclusiveStartKey(response.lastEvaluatedKey())
                            .build();
                    return page.concatWith(queryAll(client, next));
                });
    }
}

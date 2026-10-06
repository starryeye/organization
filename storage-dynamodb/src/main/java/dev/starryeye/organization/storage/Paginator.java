package dev.starryeye.organization.storage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.Map;

/**
 * DynamoDB 의 Query 는 응답이 1MB 를 넘으면 나머지를 잘라내고 LastEvaluatedKey 를 돌려준다.
 * 이 모듈의 모든 Query 는 반드시 이 값을 따라가야 한다 — 그렇지 않으면 픽스처가 작은
 * 테스트에서는 멀쩡해 보이다가, 운영 데이터에서는 결과가 조용히 잘려 나간다.
 *
 * <p>다음 쪽은 소비자가 더 요청할 때만 읽는다(소비자가 멈추면 한 쪽 앞까지만 — {@link #queryAll} 참고).
 */
final class Paginator {

    private Paginator() {
    }

    /**
     * LastEvaluatedKey 를 따라가며 전체 페이지를 이어붙인다.
     *
     * <p>{@code Flux.expand} 로 <b>반복</b>한다. 전에는 자기 자신을 다시 불러
     * {@code concatWith} 로 이어붙였는데, 그러면 페이지 수만큼 연산자가 중첩된다 —
     * 페이지가 몇 개일 때는 보이지 않다가 큰 테이블에서 신호 전파가 깊어진다.
     * {@code expand} 는 중첩 없이 평탄하게 다음 페이지를 이어간다.
     *
     * <p>{@code concatMapIterable} 이어야 한다. 페이지 순서가 곧 정렬 순서라
     * ({@code scanIndexForward=false} + {@code take(limit)} 조합이 그것에 기댄다)
     * 뒤섞이면 "최신 N건" 이 최신이 아니게 된다.
     *
     * <p><b>소비자가 요청한 만큼만 읽는다 — 많아야 한 쪽만 앞서 읽는다.</b> {@code concatMapIterable} 의 기본 prefetch 는 32 라서,
     * 소비자가 5줄만 요청하고 취소 없이 멈춰도 위쪽에 응답을 32개까지 요청해 쪽이 끝까지 읽혔다. 멤버를 흘려 쓰는 SCIM 응답은
     * 느린 클라이언트가 소켓을 붙잡고 있어도 조직 전체를 끌어오면 안 되므로 prefetch 를 1 로 묶는다(설계 2026-10-06 §4.2).
     * 쪽 순서와 취소 동작은 그대로다.
     */
    static Flux<Map<String, AttributeValue>> queryAll(DynamoDbAsyncClient client, QueryRequest request) {
        return Mono.fromFuture(() -> client.query(request))
                .expand(response -> {
                    Map<String, AttributeValue> lastKey = response.lastEvaluatedKey();
                    if (lastKey == null || lastKey.isEmpty()) {
                        return Mono.empty();
                    }
                    QueryRequest next = request.toBuilder().exclusiveStartKey(lastKey).build();
                    return Mono.fromFuture(() -> client.query(next));
                })
                .concatMapIterable(QueryResponse::items, 1);
    }
}

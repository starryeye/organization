package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.storage.DynamoDbProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

/**
 * DynamoDB 테이블에 읽기 전용 describeTable 호출로 가용성을 확인한다.
 */
@Component("dynamoDb")
@RequiredArgsConstructor
public class DynamoDbHealthIndicator implements ReactiveHealthIndicator {
    /**
     * 프로브가 응답 없이 매달리지 않게 상한을 둔다. 매달린 프로브는 죽은 것보다 나쁘다 —
     * 오케스트레이터는 DOWN 을 보면 재시작하지만, 아무 답도 없으면 그 판단조차 못 한다.
     * 상한을 넘으면 {@code onErrorResume} 이 그것을 DOWN 으로 옮긴다.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    @Override
    public Mono<Health> health() {
        return Mono.fromFuture(() -> client.describeTable(DescribeTableRequest.builder()
                        .tableName(properties.getTableName())
                        .build()))
                .map(response -> Health.up()
                        .withDetail("table", properties.getTableName())
                        .withDetail("itemCount", response.table().itemCount())
                        .build())
                .timeout(PROBE_TIMEOUT)
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}

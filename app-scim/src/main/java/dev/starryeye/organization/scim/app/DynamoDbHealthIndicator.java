package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.storage.DynamoDbProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

/**
 * DynamoDB 테이블에 읽기 전용 describeTable 호출로 가용성을 확인한다.
 */
@Component("dynamoDb")
@RequiredArgsConstructor
public class DynamoDbHealthIndicator implements ReactiveHealthIndicator {

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
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}

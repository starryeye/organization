package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.storage.DynamoDbProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 헬스 프로브가 응답 없는 의존성에 함께 매달리지 않는지 확인한다.
 *
 * <p>매달린 프로브는 죽은 것보다 나쁘다 — 오케스트레이터는 DOWN 을 보면 재시작하지만,
 * 아무 답도 없으면 그 판단조차 하지 못한다.
 */
class DynamoDbHealthIndicatorTest {

    private static DynamoDbProperties 설정() {
        var properties = new DynamoDbProperties();
        properties.setTableName("organization-test");
        return properties;
    }

    @Test
    @DisplayName("DynamoDB 가 응답하지 않으면 함께 매달리지 않고 DOWN 을 보고한다")
    void 매달리지_않고_DOWN을_보고한다() {
        // given — 영원히 완료되지 않는 응답. 타임아웃이 없으면 이 테스트가 끝나지 않는다
        var 매달리는_클라이언트 = new HangingDynamoDbClient(new CompletableFuture<>());
        var indicator = new DynamoDbHealthIndicator(매달리는_클라이언트, 설정());

        // when — 프로브 상한(2초)보다 넉넉히 기다린다
        Health health = indicator.health().block(Duration.ofSeconds(10));

        // then
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("DynamoDB 가 제때 답하면 UP 과 테이블 정보를 보고한다")
    void 제때_답하면_UP이다() {
        // given
        var 응답 = DescribeTableResponse.builder()
                .table(builder -> builder.tableName("organization-test").itemCount(42L))
                .build();
        var indicator = new DynamoDbHealthIndicator(
                new HangingDynamoDbClient(CompletableFuture.completedFuture(응답)), 설정());

        // when
        Health health = indicator.health().block(Duration.ofSeconds(10));

        // then
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("table", "organization-test");
        assertThat(health.getDetails()).containsEntry("itemCount", 42L);
    }

    /**
     * {@code describeTable} 만 지정한 미래로 답하고 나머지는 건드리지 않는다.
     * SDK 인터페이스의 다른 메서드는 기본 구현이 예외를 던지므로 호출되면 즉시 드러난다.
     */
    private record HangingDynamoDbClient(CompletableFuture<DescribeTableResponse> response)
            implements DynamoDbAsyncClient {

        @Override
        public CompletableFuture<DescribeTableResponse> describeTable(DescribeTableRequest request) {
            return response;
        }

        @Override
        public String serviceName() {
            return "dynamodb";
        }

        @Override
        public void close() {
        }
    }
}

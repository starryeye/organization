package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 클라이언트 전체 시간 한도(설계 2026-10-27 §6). 네트워크에 붙지 않는다 — 클라이언트를 만들어 설정만 읽는다.
 */
class DynamoDbConfigTest {

    private static DynamoDbProperties 설정() {
        var properties = new DynamoDbProperties();
        // 실제 AWS 자격증명 사슬을 타지 않게 Local 모양으로 만든다. 만들기만 하고 부르지 않는다
        properties.setEndpoint("http://localhost:8000");
        return properties;
    }

    private static ClientOverrideConfiguration 클라이언트의_덮어쓰기_설정(DynamoDbProperties properties) {
        try (DynamoDbAsyncClient client = new DynamoDbConfig().dynamoDbAsyncClient(properties)) {
            return client.serviceClientConfiguration().overrideConfiguration();
        }
    }

    @Test
    @DisplayName("기본값은 시도 10초, 호출 60초다")
    void 기본값은_시도_10초_호출_60초다() {
        // given
        var properties = new DynamoDbProperties();

        // when / then
        assertThat(properties.getApiCallAttemptTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getApiCallTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("클라이언트에 시도 한도와 호출 한도를 건다 — 기본값과 바꾼 값 모두")
    void 클라이언트에_시도_한도와_호출_한도를_건다() {
        // given
        var 기본 = 설정();
        var 바꾼 = 설정();
        바꾼.setApiCallAttemptTimeout(Duration.ofSeconds(3));
        바꾼.setApiCallTimeout(Duration.ofSeconds(7));

        // when
        var 기본_덮어쓰기 = 클라이언트의_덮어쓰기_설정(기본);
        var 바꾼_덮어쓰기 = 클라이언트의_덮어쓰기_설정(바꾼);

        // then
        assertThat(기본_덮어쓰기.apiCallAttemptTimeout()).contains(Duration.ofSeconds(10));
        assertThat(기본_덮어쓰기.apiCallTimeout()).contains(Duration.ofSeconds(60));
        assertThat(바꾼_덮어쓰기.apiCallAttemptTimeout()).contains(Duration.ofSeconds(3));
        assertThat(바꾼_덮어쓰기.apiCallTimeout()).contains(Duration.ofSeconds(7));
    }
}

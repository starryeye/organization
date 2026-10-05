package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DynamoDB(AWS SDK v2) 예외 하나가 일시 장애인지 알아보는 규칙 (설계 2026-10-05 §3.3).
 */
class DynamoDbTemporaryFailuresTest {

    private final DynamoDbTemporaryFailures 인식기 = new DynamoDbTemporaryFailures();

    @Test
    @DisplayName("네트워크·시간 초과(SdkClientException), 스로틀링, 5xx 는 일시 장애다 — AWS SDK 의 재시도 분류를 따른다")
    void 일시_장애를_알아본다() {
        // given
        var 네트워크 = SdkClientException.create("connection reset");
        // 스로틀링 판정은 SDK 가 응답의 오류 코드로 한다 — DynamoDB 가 실제로 싣는 코드를 단다
        var 스로틀링 = ProvisionedThroughputExceededException.builder().message("slow down").statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ProvisionedThroughputExceededException").build())
                .build();
        var 서버오류 = DynamoDbException.builder().message("internal").statusCode(500).build();

        // when, then
        assertThat(List.of(네트워크, 스로틀링, 서버오류))
                .allSatisfy(e -> assertThat(인식기.재시도_대기(e)).contains(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("조건 실패·검증 오류(4xx, 스로틀링 아님)는 일시 장애가 아니다 — 다시 보내도 같다")
    void 결정적인_실패는_아니다() {
        // given
        var 조건실패 = ConditionalCheckFailedException.builder().message("cond").statusCode(400).build();
        var 검증 = DynamoDbException.builder().message("ValidationException").statusCode(400).build();

        // when, then
        assertThat(인식기.재시도_대기(조건실패)).isEmpty();
        assertThat(인식기.재시도_대기(검증)).isEmpty();
        assertThat(인식기.재시도_대기(new IllegalStateException("x"))).isEmpty();
    }
}

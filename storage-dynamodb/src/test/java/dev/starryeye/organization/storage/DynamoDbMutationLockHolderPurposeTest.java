package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건 실패 응답에서 쥔 쪽의 용도를 읽는 규칙. 읽지 못하면 null — 호출한 쪽이 흔한 경우인 쓰기 경합(2초)으로 본다.
 * DynamoDB Local 은 기존 항목을 돌려주지 않는 판이 있어(2.5.3 은 돌려준다) 응답 모양을 직접 만들어 본다.
 */
class DynamoDbMutationLockHolderPurposeTest {

    @Test
    @DisplayName("쥔 용도를 읽을 수 없는 네 경우는 모두 null 이다 — 항목 없음, purpose 없음, 모르는 값, 문자열이 아닌 값")
    void 읽을_수_없으면_null이다() {
        // given
        var 항목_없음 = ConditionalCheckFailedException.builder().message("cond").build();
        var purpose_없음 = ConditionalCheckFailedException.builder().message("cond")
                .item(Map.of("token", AttributeValue.fromS("t"))).build();
        var 모르는_값 = ConditionalCheckFailedException.builder().message("cond")
                .item(Map.of("purpose", AttributeValue.fromS("NOT_A_PURPOSE"))).build();
        var 문자열이_아닌_값 = ConditionalCheckFailedException.builder().message("cond")
                .item(Map.of("purpose", AttributeValue.fromN("1"))).build();

        // when, then
        assertThat(DynamoDbMutationLock.쥔_용도(항목_없음)).as("항목 없음").isNull();
        assertThat(DynamoDbMutationLock.쥔_용도(purpose_없음)).as("purpose 없음").isNull();
        assertThat(DynamoDbMutationLock.쥔_용도(모르는_값)).as("모르는 값").isNull();
        assertThat(DynamoDbMutationLock.쥔_용도(문자열이_아닌_값)).as("문자열이 아닌 값").isNull();
    }

    @Test
    @DisplayName("purpose 가 아는 값이면 그 용도를 돌려준다")
    void 아는_값은_읽는다() {
        // given
        var 재적재 = ConditionalCheckFailedException.builder().message("cond")
                .item(Map.of("purpose", AttributeValue.fromS("REBUILD"))).build();

        // when, then
        assertThat(DynamoDbMutationLock.쥔_용도(재적재)).isEqualTo(LockPurpose.REBUILD);
    }
}

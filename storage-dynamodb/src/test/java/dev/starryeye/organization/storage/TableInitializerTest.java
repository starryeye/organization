package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TableInitializerTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("테이블이 없으면 PK/SK 와 GSI1 을 갖춘 테이블을 생성한다")
    void 테이블과_GSI를_생성한다() {
        // given — DynamoDbTestSupport 가 이미 ensureTable 을 호출했다

        // when
        var described = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();

        // then
        assertThat(described.keySchema()).extracting(k -> k.attributeName())
                .containsExactly(Keys.PK, Keys.SK);
        assertThat(described.globalSecondaryIndexes()).hasSize(1);
        assertThat(described.globalSecondaryIndexes().get(0).indexName()).isEqualTo(Keys.GSI1);
        assertThat(described.globalSecondaryIndexes().get(0).keySchema())
                .extracting(k -> k.attributeName())
                .containsExactly(Keys.GSI1PK, Keys.GSI1SK);
    }

    @Test
    @DisplayName("테이블이 이미 있으면 다시 생성하지 않고 조용히 통과한다")
    void 이미_있으면_다시_만들지_않는다() {
        // given, when, then
        assertThatCode(() -> new TableInitializer(client, properties).ensureTable().block())
                .doesNotThrowAnyException();
    }
}

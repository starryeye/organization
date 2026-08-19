package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TableInitializerTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("테이블이 없으면 PK/SK 와 GSI1, GSI2 를 갖춘 테이블을 생성한다")
    void 테이블과_GSI를_생성한다() {
        // given — DynamoDbTestSupport 가 이미 ensureTable 을 호출했다

        // when
        var described = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();

        // then
        assertThat(described.keySchema()).extracting(k -> k.attributeName())
                .containsExactly(Keys.PK, Keys.SK);
        assertThat(described.globalSecondaryIndexes()).extracting(i -> i.indexName())
                .containsExactlyInAnyOrder(Keys.GSI1, Keys.GSI2);

        var gsi1 = described.globalSecondaryIndexes().stream()
                .filter(i -> Keys.GSI1.equals(i.indexName())).findFirst().orElseThrow();
        assertThat(gsi1.keySchema()).extracting(k -> k.attributeName())
                .containsExactly(Keys.GSI1PK, Keys.GSI1SK);

        var gsi2 = described.globalSecondaryIndexes().stream()
                .filter(i -> Keys.GSI2.equals(i.indexName())).findFirst().orElseThrow();
        assertThat(gsi2.keySchema()).extracting(k -> k.attributeName())
                .containsExactly(Keys.GSI2PK, Keys.GSI2SK);
    }

    @Test
    @DisplayName("테이블이 이미 있으면 다시 생성하지 않고 조용히 통과한다")
    void 이미_있으면_다시_만들지_않는다() {
        // given, when, then
        assertThatCode(() -> new TableInitializer(client, properties).ensureTable().block())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GSI2 없이 만들어진 옛 테이블에 다시 초기화하면 GSI2 가 보강된다")
    void 옛_테이블에_GSI2를_보강한다() {
        // given — GSI1 만 있던 옛 시절의 테이블을 흉내낸다
        client.deleteTable(DeleteTableRequest.builder().tableName(properties.getTableName()).build()).join();
        client.createTable(CreateTableRequest.builder()
                .tableName(properties.getTableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attribute(Keys.PK), attribute(Keys.SK), attribute(Keys.GSI1PK), attribute(Keys.GSI1SK))
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.SK).keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(Keys.GSI1)
                        .keySchema(
                                KeySchemaElement.builder().attributeName(Keys.GSI1PK).keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName(Keys.GSI1SK).keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build()).join();

        // when
        new TableInitializer(client, properties).ensureTable().block();

        // then
        var described = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();
        assertThat(described.globalSecondaryIndexes()).extracting(i -> i.indexName())
                .containsExactlyInAnyOrder(Keys.GSI1, Keys.GSI2);
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}

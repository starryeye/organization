package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableInitializerTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("테이블이 없으면 PK/SK 와 GSI1, GSI2, GSI3 를 갖춘 테이블을 생성한다")
    void 테이블과_GSI를_생성한다() {
        // given — DynamoDbTestSupport 가 이미 ensureTable 을 호출했다

        // when
        var described = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();

        // then
        assertThat(described.keySchema()).extracting(k -> k.attributeName())
                .containsExactly(Keys.PK, Keys.SK);
        assertThat(described.globalSecondaryIndexes()).extracting(i -> i.indexName())
                .containsExactlyInAnyOrder(Keys.GSI1, Keys.GSI2, Keys.GSI3);

        var gsi1 = described.globalSecondaryIndexes().stream()
                .filter(i -> Keys.GSI1.equals(i.indexName())).findFirst().orElseThrow();
        assertThat(gsi1.keySchema()).extracting(k -> k.attributeName())
                .containsExactly(Keys.GSI1PK, Keys.GSI1SK);

        // GSI2 의 정렬키는 직원 META 에만 쓰는 소문자 표시명이다(설계 2026-10-08 §3.2). 글자로 적는 이유는
        // 상수가 옛 값으로 돌아가도 상수를 통한 단언은 그대로 통과하기 때문이다.
        var gsi2 = described.globalSecondaryIndexes().stream()
                .filter(i -> Keys.GSI2.equals(i.indexName())).findFirst().orElseThrow();
        assertThat(gsi2.keySchema()).extracting(k -> k.attributeName())
                .containsExactly("GSI1PK", "displayNameKey");
        // 표시명은 이제 키가 아니므로 프로젝션에 명시돼야 검색 결과의 표시명 칸이 빈칸이 되지 않는다
        assertThat(gsi2.projection().nonKeyAttributes())
                .containsExactlyInAnyOrder("userName", "displayName", "active");
    }

    @Test
    @DisplayName("테이블이 이미 있으면 다시 생성하지 않고 조용히 통과한다")
    void 이미_있으면_다시_만들지_않는다() {
        // given, when, then
        assertThatCode(() -> new TableInitializer(client, properties).ensureTable().block())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GSI2 없이 만들어진 옛 테이블이면 기동을 멈춘다 — 인덱스를 더해도 옛 직원은 새 키가 없어 실리지 않는다(설계 2026-10-08 §3.4)")
    void GSI2_없는_옛_테이블이면_멈춘다() {
        // given
        GSI2_없는_옛_테이블을_만든다();

        // when, then
        assertThatThrownBy(() -> new TableInitializer(client, properties).ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Keys.GSI2)
                .hasMessageContaining("인덱스 없음")
                .hasMessageContaining("다시 만들어야");
    }

    @Test
    @DisplayName("GSI2 정렬키가 옛 displayName 인 테이블이면 기동을 멈춘다 — 그대로 두면 표시명 검색만 실행 중에 500 이다")
    void 옛_GSI2_테이블이면_멈춘다() {
        // given — 이 슬라이드 전의 GSI2(정렬키 displayName)
        client.deleteTable(DeleteTableRequest.builder().tableName(properties.getTableName()).build()).join();
        client.createTable(CreateTableRequest.builder()
                .tableName(properties.getTableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attribute(Keys.PK), attribute(Keys.SK), attribute(Keys.GSI1PK), attribute("displayName"))
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.SK).keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(Keys.GSI2)
                        .keySchema(
                                KeySchemaElement.builder().attributeName(Keys.GSI1PK).keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("displayName").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build()).join();

        // when, then
        assertThatThrownBy(() -> new TableInitializer(client, properties).ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정렬키 displayName")
                .hasMessageContaining("다시 만들어야");
    }

    private void GSI2_없는_옛_테이블을_만든다() {
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
    }

    @Test
    @DisplayName("externalId 로 찾는 GSI3 를 키만 담아 만든다")
    void GSI3_를_만든다() {
        // when
        var table = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();

        // then
        var gsi3 = table.globalSecondaryIndexes().stream()
                .filter(index -> Keys.GSI3.equals(index.indexName()))
                .findFirst().orElseThrow();
        assertThat(gsi3.keySchema()).extracting(k -> k.attributeName()).containsExactly("externalId", "PK");
        assertThat(gsi3.projection().projectionType()).isEqualTo(ProjectionType.KEYS_ONLY);
    }

    @Test
    @DisplayName("테이블을 만들 때 테이블 TTL(expiresAt)을 켠다")
    void TTL_을_켠다() {
        // when
        var ttl = client.describeTimeToLive(DescribeTimeToLiveRequest.builder()
                .tableName(properties.getTableName()).build()).join().timeToLiveDescription();

        // then
        assertThat(ttl.attributeName()).isEqualTo(Keys.EXPIRES_AT);
        assertThat(ttl.timeToLiveStatus()).isIn(TimeToLiveStatus.ENABLED, TimeToLiveStatus.ENABLING);
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}

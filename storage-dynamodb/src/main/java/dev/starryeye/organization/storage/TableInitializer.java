package dev.starryeye.organization.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class TableInitializer implements InitializingBean {

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    @Override
    public void afterPropertiesSet() {
        if (properties.isCreateTableOnStartup()) {
            ensureTable().block();
        }
    }

    public Mono<Void> ensureTable() {
        String table = properties.getTableName();
        return Mono.fromFuture(() -> client.describeTable(DescribeTableRequest.builder()
                        .tableName(table).build()))
                .doOnNext(response -> log.info("DynamoDB 테이블 '{}' 이 이미 존재한다", table))
                .then()
                .onErrorResume(ResourceNotFoundException.class, notFound -> createTable(table));
    }

    private Mono<Void> createTable(String table) {
        log.info("DynamoDB 테이블 '{}' 을 생성한다", table);
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attribute(Keys.PK), attribute(Keys.SK),
                        attribute(Keys.GSI1PK), attribute(Keys.GSI1SK))
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
                .build();

        return Mono.fromFuture(() -> client.createTable(request)).then();
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}

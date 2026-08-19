package dev.starryeye.organization.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateGlobalSecondaryIndexAction;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexUpdate;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;
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
                .flatMap(response -> {
                    log.info("DynamoDB 테이블 '{}' 이 이미 존재한다", table);
                    return addMissingIndex(table, response);
                })
                .onErrorResume(ResourceNotFoundException.class, notFound -> createTable(table));
    }

    private Mono<Void> createTable(String table) {
        log.info("DynamoDB 테이블 '{}' 을 생성한다", table);
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attribute(Keys.PK), attribute(Keys.SK),
                        attribute(Keys.GSI1PK), attribute(Keys.GSI1SK),
                        attribute(Keys.GSI2PK), attribute(Keys.GSI2SK))
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.SK).keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder()
                                .indexName(Keys.GSI1)
                                .keySchema(
                                        KeySchemaElement.builder().attributeName(Keys.GSI1PK).keyType(KeyType.HASH).build(),
                                        KeySchemaElement.builder().attributeName(Keys.GSI1SK).keyType(KeyType.RANGE).build())
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        userDisplayNameIndex())
                .build();

        return Mono.fromFuture(() -> client.createTable(request)).then();
    }

    /**
     * 직원 표시명 접두사 검색용 인덱스.
     *
     * <p>프로젝션이 {@code ALL} 이 아니라 {@code INCLUDE} 인 이유: 검색 결과 한 줄을 그리는 데
     * 필요한 속성만 담으면 된다. {@code KEYS_ONLY} 로 더 줄이면 결과 20건마다 GetItem 20번이
     * 붙어 오히려 손해다.
     */
    private static GlobalSecondaryIndex userDisplayNameIndex() {
        return GlobalSecondaryIndex.builder()
                .indexName(Keys.GSI2)
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.GSI2PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.GSI2SK).keyType(KeyType.RANGE).build())
                .projection(Projection.builder()
                        .projectionType(ProjectionType.INCLUDE)
                        .nonKeyAttributes("userName", "displayName", "active")
                        .build())
                .build();
    }

    /**
     * 이미 있는 테이블에 GSI2 가 없으면 더한다. 기존 배포에서 표시명 검색이
     * ValidationException 으로 죽는 것을 막는다.
     *
     * <p>완료를 기다리지 않는다 — 백필 중에도 테이블 쓰기는 계속되고, 검색만 잠시 비어 보인다.
     * DynamoDB 는 한 번에 하나의 GSI 만 만들 수 있으므로 이미 만드는 중이면 그대로 둔다.
     */
    private Mono<Void> addMissingIndex(String table, DescribeTableResponse response) {
        boolean present = response.table().globalSecondaryIndexes() != null
                && response.table().globalSecondaryIndexes().stream()
                        .anyMatch(index -> Keys.GSI2.equals(index.indexName()));
        if (present) {
            return Mono.empty();
        }
        log.info("DynamoDB 테이블 '{}' 에 인덱스 '{}' 를 추가한다", table, Keys.GSI2);
        GlobalSecondaryIndex index = userDisplayNameIndex();
        UpdateTableRequest request = UpdateTableRequest.builder()
                .tableName(table)
                .attributeDefinitions(attribute(Keys.GSI2PK), attribute(Keys.GSI2SK))
                .globalSecondaryIndexUpdates(GlobalSecondaryIndexUpdate.builder()
                        .create(CreateGlobalSecondaryIndexAction.builder()
                                .indexName(index.indexName())
                                .keySchema(index.keySchema())
                                .projection(index.projection())
                                .build())
                        .build())
                .build();
        return Mono.fromFuture(() -> client.updateTable(request))
                .doOnError(error -> log.warn("인덱스 '{}' 추가 실패 — 표시명 검색이 동작하지 않는다", Keys.GSI2, error))
                .onErrorResume(error -> Mono.empty())
                .then();
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}

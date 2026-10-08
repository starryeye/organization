package dev.starryeye.organization.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.util.List;

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
                    return requireCurrentDisplayNameIndex(table, response);
                })
                .onErrorResume(ResourceNotFoundException.class, notFound -> createTable(table));
    }

    private Mono<Void> createTable(String table) {
        log.info("DynamoDB 테이블 '{}' 을 생성한다", table);
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                // GSI2PK 는 GSI1PK 와 같은 속성이라 여기 다시 적지 않는다 —
                // 같은 속성을 두 번 정의하면 ValidationException 이다.
                .attributeDefinitions(
                        attribute(Keys.PK), attribute(Keys.SK),
                        attribute(Keys.GSI1PK), attribute(Keys.GSI1SK),
                        attribute(Keys.GSI2SK), attribute(Keys.GSI3PK))
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
                        userDisplayNameIndex(),
                        externalIdIndex())
                .build();

        // TTL 은 테이블이 ACTIVE 가 된 뒤에만 켤 수 있다 — AWS 에서는 생성 직후 CREATING 이다
        return Mono.fromFuture(() -> client.createTable(request))
                .then(Mono.usingWhen(
                        Mono.fromSupplier(client::waiter),
                        waiter -> Mono.fromFuture(() -> waiter.waitUntilTableExists(
                                DescribeTableRequest.builder().tableName(table).build())),
                        waiter -> Mono.fromRunnable(waiter::close)))
                .then(Mono.fromFuture(() -> client.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(table)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .attributeName(Keys.EXPIRES_AT)
                                .enabled(true)
                                .build())
                        .build())))
                .then();
    }

    /**
     * 직원 표시명 접두사 검색용 인덱스. 파티션키는 {@link Keys#GSI1PK} 를 그대로 쓰고, 정렬키는 직원 META 에만 쓰는
     * 소문자 표시명({@link Keys#GSI2SK})이다 — 그래서 조직 META 는 이 인덱스에 실리지 않는다({@link Keys#GSI2PK} 의 설명을 보라).
     *
     * <p>프로젝션이 {@code ALL} 이 아니라 {@code INCLUDE} 인 이유: 검색 결과 한 줄을 그리는 데
     * 필요한 속성만 담으면 된다. {@code KEYS_ONLY} 로 더 줄이면 결과 20건마다 GetItem 20번이
     * 붙어 오히려 손해다. 키 속성(PK/SK/GSI1PK/displayNameKey)은 자동으로 실리므로 여기 적으면
     * 안 된다 — 인덱스 키 속성을 {@code NonKeyAttributes} 에 적으면 ValidationException 이다.
     * {@code displayName} 은 이제 키가 아니므로 여기 적어야 검색 결과의 표시명 칸이 채워진다.
     *
     * <p><b>프로젝션 목록은 인덱스가 <em>생성될 때</em> 한 번 굳는다.</b> 나중에 속성 이름을
     * 바꾸면 새로 만드는 테이블에서는 통과하지만 이미 인덱스가 있는 기존 테이블에서는 검색
     * 결과의 그 칸이 조용히 비게 된다. 여기 적힌 이름은 다른 두 곳과 반드시 같아야 한다 —
     * {@code DynamoDbDirectoryStateRepository} 의 {@code USER_NAME}/{@code DISPLAY_NAME}/
     * {@code ACTIVE} 상수(쓰는 쪽)와 {@code DynamoDbDirectorySearchRepository.toUserSummary}
     * (읽는 쪽). 셋 중 하나만 바꾸면 컴파일은 통과하고 검색만 망가진다.
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
     * {@code externalId} 로 찾는 인덱스. {@code KEYS_ONLY} 인 이유 — 찾은 {@code PK} 로 본 테이블을 GetItem 해
     * 최신 값을 읽는다. 인덱스가 늦어도 낡은 속성을 돌려주지 않고, 인덱스가 작다(S-1 설계 §5.2).
     *
     * <p>기존 테이블에 없으면 더하는 경로는 두지 않는다 — S-1 은 GSI1 키 값도 바꾸므로
     * 기존 테이블은 어차피 재생성해야 한다(설계 §5.4).
     */
    private static GlobalSecondaryIndex externalIdIndex() {
        return GlobalSecondaryIndex.builder()
                .indexName(Keys.GSI3)
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.GSI3PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.GSI3SK).keyType(KeyType.RANGE).build())
                .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                .build();
    }

    /**
     * 이미 있는 테이블의 GSI2 가 이 버전의 모양인지 본다(설계 2026-10-08 §3.4). 없거나 정렬키가 {@link Keys#GSI2SK} 가 아니면 기동을 멈춘다.
     *
     * <p>더해 주지 않는 까닭: 새 키 속성은 옛 아이템에 없어 인덱스를 더해도 백필이 옛 직원을 싣지 못한다 — 표시명 검색이 조용히 빈다.
     * 옛 GSI2 를 그대로 두면 표시명 검색만 실행 중에 {@code ValidationException}(500)이다. 운영 배포 전이라 테이블을 다시 만든다.
     */
    private Mono<Void> requireCurrentDisplayNameIndex(String table, DescribeTableResponse response) {
        List<GlobalSecondaryIndexDescription> indexes = response.table().globalSecondaryIndexes() == null
                ? List.of() : response.table().globalSecondaryIndexes();
        String sortKey = indexes.stream()
                .filter(index -> Keys.GSI2.equals(index.indexName()))
                .findFirst()
                .flatMap(index -> index.keySchema().stream()
                        .filter(key -> key.keyType() == KeyType.RANGE)
                        .findFirst())
                .map(KeySchemaElement::attributeName)
                .orElse(null);
        if (Keys.GSI2SK.equals(sortKey)) {
            return Mono.empty();
        }
        return Mono.error(new IllegalStateException(
                "테이블 '%s' 의 인덱스 %s 가 이 버전과 다르다(정렬키 %s) — 테이블을 다시 만들어야 한다".formatted(
                        table, Keys.GSI2, sortKey == null ? "인덱스 없음" : sortKey)));
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}

package dev.starryeye.organization.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ContinuousBackupsUnavailableException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeContinuousBackupsRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PointInTimeRecoverySpecification;
import software.amazon.awssdk.services.dynamodb.model.PointInTimeRecoveryStatus;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateContinuousBackupsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * 기동 때 DynamoDB 테이블을 확인한다(설계 2026-10-27 §3, D1). 생성 여부({@code dynamodb.create-table-on-startup})와
 * 상관없이 늘 DescribeTable 부터 한다.
 *
 * <ul>
 *   <li>없을 때: 생성이 꺼져 있으면 기동을 멈춘다 — 이름 오타가 빈 테이블을 새로 만들어 신원이 갈라지지 않게. 켜져 있으면 만든다.
 *       {@code require-protection} 이면 삭제 보호를 켜서 만들고 PITR 도 켠다.</li>
 *   <li>있을 때: 모양(GSI 키·GSI2 프로젝션·TTL)이 이 버전과 다르면 기동을 멈춘다. {@code require-protection} 이면
 *       삭제 보호와 PITR 이 켜져 있는지도 본다. PITR 복원본이면 경고만 한다.</li>
 * </ul>
 *
 * <p>운영 배포 전이라 옛 모양의 테이블은 이관하지 않는다 — 처방은 "테이블을 다시 만든다" 다.
 */
@Slf4j
public class TableInitializer implements InitializingBean {

    /**
     * GSI2 가 싣는 비키 속성. 생성({@link #userDisplayNameIndex})과 모양 검사가 이 하나를 함께 쓴다.
     * {@code externalId} 는 직원 응답에 싣는 값이다(D9, 설계 2026-10-27 §11).
     */
    static final List<String> GSI2_PROJECTION = List.of("userName", "displayName", "active", "externalId");

    private static final String 없음 = "없음";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Duration pitr간격;
    private final Duration pitr한도;

    public TableInitializer(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        this(client, properties, Duration.ofSeconds(5), Duration.ofMinutes(2));
    }

    /** 시험이 PITR 켜기 재시도의 간격과 한도를 줄여 쓴다. */
    TableInitializer(DynamoDbAsyncClient client, DynamoDbProperties properties, Duration pitr간격, Duration pitr한도) {
        this.client = client;
        this.properties = properties;
        this.pitr간격 = pitr간격;
        this.pitr한도 = pitr한도;
    }

    @Override
    public void afterPropertiesSet() {
        // 이름 기본값이 없다 — 키를 빠뜨린 배포가 describe 에서 null 로 터지지 않고 무엇이 비었는지 말하며 멈춘다
        String table = properties.getTableName();
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("dynamodb.table-name 이 비어 있다 — 설정에 테이블 이름을 적는다");
        }
        ensureTable().block();
    }

    public Mono<Void> ensureTable() {
        String table = properties.getTableName();
        // "없음" 은 describe 한 번에만 해당한다 — 아래 검사·생성이 던지는 ResourceNotFoundException 을 없음으로 읽지 않는다
        return Mono.fromFuture(() -> client.describeTable(DescribeTableRequest.builder()
                        .tableName(table).build()))
                .map(response -> Optional.of(response.table()))
                .onErrorReturn(ResourceNotFoundException.class, Optional.empty())
                .flatMap(described -> described
                        .map(description -> 있는_테이블을_검사한다(table, description))
                        .orElseGet(() -> 없는_테이블을_처리한다(table)));
    }

    private Mono<Void> 없는_테이블을_처리한다(String table) {
        if (!properties.isCreateTableOnStartup()) {
            return Mono.error(new IllegalStateException(
                    "DynamoDB 테이블 %s 이 없다 — 처음 설치라면 dynamodb.create-table-on-startup=true 로 한 번 띄우거나 미리 만든다"
                            .formatted(table)));
        }
        return createTable(table);
    }

    private Mono<Void> 있는_테이블을_검사한다(String table, TableDescription description) {
        log.info("DynamoDB 테이블 '{}' 이 이미 존재한다", table);
        return 모양을_검사한다(table, description)
                .then(Mono.defer(() -> properties.isRequireProtection()
                        ? 보호를_확인한다(table, description) : Mono.<Void>empty()))
                .then(Mono.<Void>fromRunnable(() -> {
                    if (description.restoreSummary() != null) {
                        log.warn("PITR 로 복원한 테이블이다 — 장부보다 낡은 기준선일 수 있어 재적재를 권한다(SCIM 은 mode=tuples)");
                    }
                }));
    }

    /** 요청의 모양을 한곳에 둔다 — 시험이 옛 모양의 테이블을 만들 때 이 요청을 고쳐 쓴다. */
    static CreateTableRequest 테이블_요청(String table, boolean 삭제_보호) {
        return CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .deletionProtectionEnabled(삭제_보호)
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
    }

    private Mono<Void> createTable(String table) {
        boolean 보호 = properties.isRequireProtection();
        log.info("DynamoDB 테이블 '{}' 을 생성한다{}", table, 보호 ? " (삭제 보호 켬)" : "");
        CreateTableRequest request = 테이블_요청(table, 보호);

        // TTL 은 테이블이 ACTIVE 가 된 뒤에만 켤 수 있다 — AWS 에서는 생성 직후 CREATING 이다
        Mono<Void> 만들기 = Mono.fromFuture(() -> client.createTable(request))
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
        return 보호 ? 만들기.then(pitr를_켠다(table)) : 만들기;
    }

    /**
     * PITR 을 켠다. 실제 AWS 는 막 만든 테이블에 이 요청을 한동안 {@link ContinuousBackupsUnavailableException} 으로
     * 거절한다고 알려져 있다(확인하지 못함). 그 예외만 {@code pitr간격} 으로 쉬며 {@code pitr한도} 안에서 다시 시도한다.
     * 끝내 켜지 못하면 기동을 멈춘다 — 만든 테이블은 남고, 다음 기동은 보호 확인에서 같은 처방을 다시 보인다.
     */
    private Mono<Void> pitr를_켠다(String table) {
        return Mono.defer(() -> {
                    long 시작 = System.nanoTime();
                    return Mono.fromFuture(() -> client.updateContinuousBackups(UpdateContinuousBackupsRequest.builder()
                                    .tableName(table)
                                    .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                                            .pointInTimeRecoveryEnabled(true).build())
                                    .build()))
                            .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, pitr간격)
                                    .filter(e -> e instanceof ContinuousBackupsUnavailableException
                                            && Duration.ofNanos(System.nanoTime() - 시작).compareTo(pitr한도) < 0)
                                    .doBeforeRetry(signal -> log.info(
                                            "테이블 '{}' 이 아직 PITR 을 받지 않는다 — {} 뒤 다시 시도한다", table, pitr간격)))
                            .then();
                })
                .doOnSuccess(done -> log.info("테이블 '{}' 의 PITR 을 켰다", table))
                .onErrorMap(e -> new IllegalStateException(
                        "테이블은 만들었으나 PITR 을 켜지 못했다 — 콘솔·CLI 로 켠 뒤 다시 띄운다", e));
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
     * {@code externalId} 는 GSI3 의 파티션키지만 이 인덱스에서는 키가 아니므로 적을 수 있다 — 직원 응답이
     * 본 테이블을 다시 읽지 않고 이 인덱스에서 바로 싣는다(D9, 설계 2026-10-27 §11).
     *
     * <p><b>프로젝션 목록은 인덱스가 <em>생성될 때</em> 한 번 굳는다.</b> 나중에 속성 이름을
     * 바꾸면 새로 만드는 테이블에서는 통과하지만 이미 인덱스가 있는 기존 테이블에서는 검색
     * 결과의 그 칸이 조용히 비게 된다. 그래서 {@link #GSI2_PROJECTION} 하나를 생성과 모양 검사가 함께 쓴다 —
     * 목록이 바뀌면 옛 테이블은 기동 때 멈춘다. 여기 적힌 이름은 다른 두 곳과 반드시 같아야 한다 —
     * {@code DynamoDbDirectoryStateRepository} 의 {@code USER_NAME}/{@code DISPLAY_NAME}/
     * {@code ACTIVE}/{@code EXTERNAL_ID} 상수(쓰는 쪽)와 {@code DynamoDbDirectorySearchRepository.toUserSummary}
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
                        .nonKeyAttributes(GSI2_PROJECTION)
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
     * 이미 있는 테이블의 모양이 이 버전의 것인지 본다(설계 2026-10-08 §3.4, 2026-10-27 §3). 다른 점을 모두 모아 한 번에 낸다:
     * GSI1·2·3 의 유무와 키, GSI2 프로젝션({@link #GSI2_PROJECTION}), TTL({@link Keys#EXPIRES_AT}).
     *
     * <p>더해 주지 않는 까닭: 새 키 속성은 옛 아이템에 없어 인덱스를 더해도 백필이 옛 직원을 싣지 못한다 — 표시명 검색이 조용히 빈다.
     * 옛 GSI2 를 그대로 두면 표시명 검색만 실행 중에 {@code ValidationException}(500)이고, 프로젝션이 옛 셋이면 검색 결과의 그 칸이 비게 된다.
     * TTL 을 빠뜨린 테이블(손으로 미리 만들 때)은 실행 기록·감사 이력·책갈피가 끝없이 쌓인다. 운영 배포 전이라 테이블을 다시 만든다.
     */
    private Mono<Void> 모양을_검사한다(String table, TableDescription description) {
        return Mono.fromFuture(() -> client.describeTimeToLive(DescribeTimeToLiveRequest.builder()
                        .tableName(table).build()))
                .map(response -> {
                    List<String> 다른_점 = new ArrayList<>(인덱스가_다른_점(description));
                    ttl이_다른_점(response.timeToLiveDescription()).ifPresent(다른_점::add);
                    return 다른_점;
                })
                .flatMap(다른_점 -> 다른_점.isEmpty() ? Mono.<Void>empty() : Mono.<Void>error(new IllegalStateException(
                        "테이블 '%s' 의 모양이 이 버전과 다르다 — %s — 테이블을 다시 만들어야 한다".formatted(
                                table, String.join(", ", 다른_점)))));
    }

    private record IndexShape(String name, String partitionKey, String sortKey) {
    }

    private static List<String> 인덱스가_다른_점(TableDescription description) {
        List<GlobalSecondaryIndexDescription> indexes = description.hasGlobalSecondaryIndexes()
                ? description.globalSecondaryIndexes() : List.of();
        List<String> 다른_점 = new ArrayList<>();
        for (IndexShape expected : List.of(
                new IndexShape(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK),
                new IndexShape(Keys.GSI2, Keys.GSI2PK, Keys.GSI2SK),
                new IndexShape(Keys.GSI3, Keys.GSI3PK, Keys.GSI3SK))) {
            Optional<GlobalSecondaryIndexDescription> found = indexes.stream()
                    .filter(index -> expected.name().equals(index.indexName()))
                    .findFirst();
            if (found.isEmpty()) {
                다른_점.add("%s 인덱스 없음".formatted(expected.name()));
                continue;
            }
            GlobalSecondaryIndexDescription index = found.get();
            String partitionKey = keyName(index, KeyType.HASH);
            if (!expected.partitionKey().equals(partitionKey)) {
                다른_점.add("%s 파티션키 %s(기대 %s)".formatted(expected.name(), partitionKey, expected.partitionKey()));
            }
            String sortKey = keyName(index, KeyType.RANGE);
            if (!expected.sortKey().equals(sortKey)) {
                다른_점.add("%s 정렬키 %s(기대 %s)".formatted(expected.name(), sortKey, expected.sortKey()));
            }
            if (Keys.GSI2.equals(expected.name())) {
                gsi2프로젝션이_다른_점(index).ifPresent(다른_점::add);
            }
        }
        return 다른_점;
    }

    private static String keyName(GlobalSecondaryIndexDescription index, KeyType type) {
        return index.keySchema().stream()
                .filter(key -> key.keyType() == type)
                .findFirst()
                .map(KeySchemaElement::attributeName)
                .orElse(없음);
    }

    /** GSI2 는 INCLUDE 이고 비키 속성 집합이 {@link #GSI2_PROJECTION} 과 같아야 한다. */
    private static Optional<String> gsi2프로젝션이_다른_점(GlobalSecondaryIndexDescription index) {
        Projection projection = index.projection();
        ProjectionType type = projection == null ? null : projection.projectionType();
        List<String> actual = projection == null ? List.of() : projection.nonKeyAttributes();
        if (type == ProjectionType.INCLUDE && new HashSet<>(actual).equals(new HashSet<>(GSI2_PROJECTION))) {
            return Optional.empty();
        }
        return Optional.of("GSI2 프로젝션 %s %s(기대 INCLUDE %s)".formatted(
                type, actual.stream().sorted().toList(), GSI2_PROJECTION.stream().sorted().toList()));
    }

    /** TTL 은 {@link Keys#EXPIRES_AT} 에 ENABLED(켜는 중인 ENABLING 포함)여야 한다. */
    private static Optional<String> ttl이_다른_점(TimeToLiveDescription ttl) {
        String attribute = ttl == null ? null : ttl.attributeName();
        TimeToLiveStatus status = ttl == null ? null : ttl.timeToLiveStatus();
        boolean 켜짐 = Keys.EXPIRES_AT.equals(attribute)
                && (status == TimeToLiveStatus.ENABLED || status == TimeToLiveStatus.ENABLING);
        if (켜짐) {
            return Optional.empty();
        }
        return Optional.of("TTL %s %s(기대 expiresAt ENABLED)".formatted(attribute == null ? 없음 : attribute, status));
    }

    /**
     * {@code require-protection} 일 때 삭제 보호와 PITR 이 둘 다 켜져 있어야 한다. 꺼진 것을 모두 적어 멈춘다 —
     * 이미 있는 테이블의 보호는 앱이 켜 주지 않고 사람이 켠다(콘솔·CLI).
     */
    private Mono<Void> 보호를_확인한다(String table, TableDescription description) {
        boolean 삭제_보호 = Boolean.TRUE.equals(description.deletionProtectionEnabled());
        return Mono.fromFuture(() -> client.describeContinuousBackups(DescribeContinuousBackupsRequest.builder()
                        .tableName(table).build()))
                .map(response -> {
                    var backups = response.continuousBackupsDescription();
                    var pitr = backups == null ? null : backups.pointInTimeRecoveryDescription();
                    return pitr != null && pitr.pointInTimeRecoveryStatus() == PointInTimeRecoveryStatus.ENABLED;
                })
                .flatMap(pitr켜짐 -> {
                    List<String> 꺼진_것 = new ArrayList<>();
                    if (!삭제_보호) {
                        꺼진_것.add("삭제 보호");
                    }
                    if (!pitr켜짐) {
                        꺼진_것.add("PITR");
                    }
                    return 꺼진_것.isEmpty() ? Mono.<Void>empty() : Mono.<Void>error(new IllegalStateException(
                            "테이블 '%s' 의 보호가 꺼져 있다: %s — 콘솔·CLI 로 켠 뒤 다시 띄운다".formatted(
                                    table, String.join(", ", 꺼진_것))));
                });
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}

package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.query.UserSummary;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.time.Duration;
import java.util.Map;

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

        // GSI2 는 전용 키 속성을 만들지 않고 기존 속성을 그대로 쓴다. 이 이름들을 상수가 아니라
        // 글자로 적는 이유는, 전용 속성으로 되돌아가면(= 기존 아이템이 백필에서 빠지면) 상수를
        // 통한 단언은 그대로 통과해 버리기 때문이다.
        var gsi2 = described.globalSecondaryIndexes().stream()
                .filter(i -> Keys.GSI2.equals(i.indexName())).findFirst().orElseThrow();
        assertThat(gsi2.keySchema()).extracting(k -> k.attributeName())
                .containsExactly("GSI1PK", "displayName");
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
        GSI2_없는_옛_테이블을_만든다();

        // when
        new TableInitializer(client, properties).ensureTable().block();

        // then
        var described = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();
        assertThat(described.globalSecondaryIndexes()).extracting(i -> i.indexName())
                .containsExactlyInAnyOrder(Keys.GSI1, Keys.GSI2);
    }

    /**
     * 인덱스가 <b>생기는지</b>가 아니라 <b>기존 아이템이 실리는지</b>를 못박는다.
     *
     * <p>다른 테스트는 전부 테이블을 새로 만든 뒤 {@code saveUser} 를 부르므로, 아이템이 언제나
     * 이미 GSI2 를 갖춘 테이블을 향해 쓰인다. 그래서 "이 브랜치보다 먼저 쓰인 아이템" 이라는
     * 실제 배포 상황이 한 번도 재현되지 않았다. 여기서는 그 아이템을 손으로 만들어 둔다.
     *
     * <p>app-scim 에는 전량 재기록 경로가 없다({@code FullSyncUseCase} 도
     * {@code RebuildUseCase} 도 배선돼 있지 않다). 그러므로 DynamoDB 의 백필만으로 기존 직원이
     * 표시명 검색에 잡혀야 하고, 잡히지 않으면 그 배포에서 표시명 검색은 <b>영구히</b> 빈
     * 결과를 낸다 — IdP 가 우연히 건드린 직원만 하나씩 새어 들어올 뿐이다.
     */
    @Test
    @DisplayName("이 브랜치 이전에 쓰인 직원도 인덱스 보강만으로 표시명 검색에 잡힌다")
    void 옛_아이템도_표시명으로_찾힌다() {
        // given — GSI2 를 모르던 시절의 테이블에, GSI2 키 속성 없이 쓰인 직원 아이템
        GSI2_없는_옛_테이블을_만든다();
        client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(Map.of(
                        Keys.PK, Attrs.s(Keys.userPk("gd.hong")),
                        Keys.SK, Attrs.s(Keys.META),
                        Keys.GSI1PK, Attrs.s(Keys.USER_INDEX),
                        Keys.GSI1SK, Attrs.s("gd.hong"),
                        "userName", Attrs.s("gd.hong"),
                        "displayName", Attrs.s("홍길동"),
                        "active", Attrs.bool(true)))
                .build()).join();

        // when — 운영자 개입 없이 기동 경로만 탄다
        new TableInitializer(client, properties).ensureTable().block();
        GSI2가_백필을_마칠_때까지_기다린다();

        // then
        var page = new DynamoDbDirectorySearchRepository(client, properties)
                .searchUsersByDisplayName("홍", null, 20).block();
        assertThat(page.items()).extracting(UserSummary::employeeId).containsExactly("gd.hong");
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

    /** 인덱스 추가는 비동기다. 백필이 끝나기 전에 조회하면 아직 비어 있는 것이 정상이다. */
    private void GSI2가_백필을_마칠_때까지_기다린다() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var described = client.describeTable(DescribeTableRequest.builder()
                            .tableName(properties.getTableName()).build()).join().table();
                    var gsi2 = described.globalSecondaryIndexes().stream()
                            .filter(i -> Keys.GSI2.equals(i.indexName())).findFirst().orElseThrow();
                    assertThat(gsi2.indexStatus()).isEqualTo(IndexStatus.ACTIVE);
                    assertThat(gsi2.backfilling()).isNotEqualTo(Boolean.TRUE);
                });
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}

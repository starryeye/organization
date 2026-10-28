package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fixture.Containers;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;

import java.net.URI;
import java.util.concurrent.CompletionException;

/**
 * DynamoDB Local 컨테이너를 한 번 띄우고 테스트마다 테이블을 새로 만든다.
 * 컨테이너 기동이 느리므로 클래스마다 띄우지 않고 static 으로 공유한다.
 */
@Testcontainers
public abstract class DynamoDbTestSupport {

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    protected DynamoDbAsyncClient client;
    protected DynamoDbProperties properties;

    @BeforeEach
    void 테이블을_새로_만든다() {
        String endpoint = "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000);

        properties = new DynamoDbProperties();
        properties.setEndpoint(endpoint);
        properties.setTableName("organization-test");
        properties.setCreateTableOnStartup(true);

        client = DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();

        dropTableIfExists();
        new TableInitializer(client, properties).ensureTable().block();
    }

    /**
     * 테이블을 지운다. 삭제 보호가 켜져 있으면 먼저 끈다 — 운영 설정(D1)으로 만든 테이블이 다음 시험으로 남아도 지워진다.
     * 없으면 아무것도 하지 않는다.
     */
    protected void dropTableIfExists() {
        String table = properties.getTableName();
        try {
            var described = client.describeTable(DescribeTableRequest.builder().tableName(table).build()).join().table();
            if (Boolean.TRUE.equals(described.deletionProtectionEnabled())) {
                client.updateTable(UpdateTableRequest.builder()
                        .tableName(table).deletionProtectionEnabled(false).build()).join();
            }
            client.deleteTable(DeleteTableRequest.builder().tableName(table).build()).join();
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof ResourceNotFoundException)) {
                throw e;
            }
        }
    }
}

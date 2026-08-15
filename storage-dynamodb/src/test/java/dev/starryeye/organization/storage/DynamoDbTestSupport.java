package dev.starryeye.organization.storage;

import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.net.URI;
import java.util.concurrent.CompletionException;

/**
 * DynamoDB Local 컨테이너를 한 번 띄우고 테스트마다 테이블을 새로 만든다.
 * 컨테이너 기동이 느리므로 클래스마다 띄우지 않고 static 으로 공유한다.
 */
@Testcontainers
public abstract class DynamoDbTestSupport {

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    protected DynamoDbAsyncClient client;
    protected DynamoDbProperties properties;

    @BeforeEach
    void 테이블을_새로_만든다() {
        String endpoint = "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000);

        properties = new DynamoDbProperties();
        properties.setEndpoint(endpoint);
        properties.setTableName("organization-test");
        properties.setCreateTableOnStartup(false);

        client = DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();

        dropTableIfExists();
        new TableInitializer(client, properties).ensureTable().block();
    }

    private void dropTableIfExists() {
        try {
            client.deleteTable(DeleteTableRequest.builder()
                    .tableName(properties.getTableName()).build()).join();
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof ResourceNotFoundException)) {
                throw e;
            }
        }
    }
}

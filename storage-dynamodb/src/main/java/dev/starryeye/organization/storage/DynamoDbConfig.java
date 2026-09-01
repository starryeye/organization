package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.port.MutationLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    public DynamoDbAsyncClient dynamoDbAsyncClient(DynamoDbProperties properties) {
        var builder = DynamoDbAsyncClient.builder().region(Region.of(properties.getRegion()));
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            // DynamoDB Local 은 자격증명을 검증하지 않지만 SDK 가 존재 자체는 요구한다
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }

    @Bean
    public TableInitializer tableInitializer(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        return new TableInitializer(client, properties);
    }

    @Bean
    public DynamoDbDirectoryStateRepository dynamoDbDirectoryStateRepository(
            DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        return new DynamoDbDirectoryStateRepository(client, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DynamoDbTupleSnapshotRepository dynamoDbTupleSnapshotRepository(
            DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        return new DynamoDbTupleSnapshotRepository(client, properties, clock);
    }

    @Bean
    public DynamoDbSyncRunRepository dynamoDbSyncRunRepository(
            DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        return new DynamoDbSyncRunRepository(client, properties, clock);
    }

    @Bean
    public DirectorySearchRepository directorySearchRepository(DynamoDbAsyncClient client,
                                                               DynamoDbProperties properties) {
        return new DynamoDbDirectorySearchRepository(client, properties);
    }

    /**
     * 인스턴스 식별자. 배제 판단에는 쓰지 않고 "누가 쥐고 있나" 를 로그로 보기 위한 값이라
     * 재시작마다 달라져도 무방하다.
     */
    @Bean
    public MutationLock mutationLock(DynamoDbAsyncClient client, DynamoDbProperties properties,
                                     Clock clock) {
        return new DynamoDbMutationLock(client, properties, clock,
                java.util.UUID.randomUUID().toString());
    }
}

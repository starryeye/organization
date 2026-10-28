package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.AuditLog;
import dev.starryeye.organization.core.port.DailyJobClaims;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

    /**
     * 클라이언트 전체의 시간 한도를 건다(설계 2026-10-27 §6). 재시도 정책은 SDK 기본 그대로다 — 한도만 걸어,
     * 스로틀이 이어질 때 호출 하나가 SDK 재시도로 수십 초 매달리지 않게 한다. 요청마다 더 짧은 한도를 줄 수 있다.
     */
    @Bean(destroyMethod = "close")
    public DynamoDbAsyncClient dynamoDbAsyncClient(DynamoDbProperties properties) {
        var builder = DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(properties.getApiCallAttemptTimeout())
                        .apiCallTimeout(properties.getApiCallTimeout())
                        .build());
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

    /** 변경 이력(설계 2026-10-10). 두 앱의 유스케이스가 쓰고, 관리 API 가 읽는다. */
    @Bean
    public AuditLog auditLog(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        return new DynamoDbAuditLog(client, properties);
    }

    @Bean
    public DirectorySearchRepository directorySearchRepository(DynamoDbAsyncClient client,
                                                               DynamoDbProperties properties) {
        return new DynamoDbDirectorySearchRepository(client, properties);
    }

    @Bean
    public DirectoryQueryRepository directoryQueryRepository(DynamoDbAsyncClient client,
                                                             DynamoDbProperties properties,
                                                             DynamoDbDirectoryStateRepository state) {
        return new DynamoDbDirectoryQueryRepository(client, properties, state);
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

    @Bean
    public PageBookmarkRepository pageBookmarkRepository(DynamoDbAsyncClient client,
                                                         DynamoDbProperties properties,
                                                         Clock clock) {
        return new DynamoDbPageBookmarkRepository(client, properties, clock);
    }

    @Bean
    public DailyJobClaims dailyJobClaims(DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        return new DynamoDbDailyJobClaims(client, properties, clock);
    }

    /** DynamoDB 예외가 일시 장애인지 알아본다 — 앱의 오류 분류기가 모아서 쓴다(설계 2026-10-05 §3.3). */
    @Bean
    public TemporaryFailureRecognizer dynamoDbTemporaryFailures() {
        return new DynamoDbTemporaryFailures();
    }
}

package dev.starryeye.organization.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("dynamodb")
public class DynamoDbProperties {

    /** DynamoDB Local 주소. 비우면 실제 AWS 엔드포인트를 쓴다 */
    private String endpoint;
    private String region = "ap-northeast-2";
    private String tableName = "organization";
    private boolean createTableOnStartup = true;
    private int snapshotRetentionDays = 7;
    private int syncrunRetentionDays = 30;

    /** 락 리스 길이. SCIM 쓰기 p99 보다 한참 길어야 한다 — 짧으면 살아있는데 만료된다. */
    private Duration lockTtl = Duration.ofSeconds(30);

    /** 락 획득 대기 한도. 넘으면 503 이 나가고 IdP 가 재시도한다. */
    private Duration lockAcquireTimeout = Duration.ofSeconds(3);

    /** 재적재처럼 오래 쥐는 작업의 갱신 주기. TTL 보다 충분히 짧아야 한다. */
    private Duration lockRenewInterval = Duration.ofSeconds(10);
}

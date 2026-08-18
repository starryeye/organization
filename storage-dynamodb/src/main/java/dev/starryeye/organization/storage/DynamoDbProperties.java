package dev.starryeye.organization.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
}

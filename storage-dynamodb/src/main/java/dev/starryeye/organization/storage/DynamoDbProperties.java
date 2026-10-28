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

    /**
     * 기동 때 테이블이 없으면 만든다(설계 2026-10-27 §3). 기본값은 꺼짐이다 — 이름 오타가 빈 테이블을 새로 만들어
     * 신원이 갈라지지 않게, 운영은 만들지 않고 멈춘다. 로컬 {@code application.yml} 만 켠다.
     * 켜든 끄든 기동 때 테이블은 늘 확인한다.
     */
    private boolean createTableOnStartup = false;

    /**
     * 운영 보호를 요구한다(설계 2026-10-27 §3). 켜면 만들 때 삭제 보호와 PITR 을 켜고, 이미 있는 테이블은 둘이 켜져 있는지 확인한다.
     * 하나라도 꺼져 있으면 기동을 멈춘다. {@code application-prod.yml} 만 켠다.
     */
    private boolean requireProtection = false;

    private int snapshotRetentionDays = 7;
    private int syncrunRetentionDays = 30;

    /** 변경 이력 보관 일수(설계 2026-10-10 §6). 동기화 이력과 같게 둔다 — 줄의 원인 회차로 거슬러 갈 곳이 끝까지 있어야 한다. */
    private int auditRetentionDays = 30;

    /** 락 리스 길이. SCIM 쓰기 p99 보다 한참 길어야 한다 — 짧으면 살아있는데 만료된다. */
    private Duration lockTtl = Duration.ofSeconds(30);

    /** 락 획득 대기 한도. 넘으면 503 이 나가고 IdP 가 재시도한다. */
    private Duration lockAcquireTimeout = Duration.ofSeconds(3);

    /** 재적재처럼 오래 쥐는 작업의 갱신 주기. TTL 보다 충분히 짧아야 한다. */
    private Duration lockRenewInterval = Duration.ofSeconds(10);
}

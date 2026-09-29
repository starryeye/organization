package dev.starryeye.organization.ldap.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("sync")
public class SyncProperties {

    private String cron = "0 0 3 * * *";
    private String purgeCron = "0 0 4 * * *";
    private DeletionGuardConfig deletionGuard = new DeletionGuardConfig();

    /**
     * 동기화·재적재 한 번의 전체 기한(설계 2026-09-29 §5). 넘으면 남은 일을 멈추고 FAILED("기한 초과 — N분")로 기록한 뒤 가드를
     * 푼다. 10만 명 재적재(쓰기 약 1,100 배치 + 장부 훑기 약 1,100 호출)를 수 분으로 추정해 넉넉히 잡았다.
     */
    private Duration jobTimeout = Duration.ofMinutes(30);

    @Getter
    @Setter
    public static class DeletionGuardConfig {
        private boolean enabled = true;
        private double thresholdRatio = 0.3;
        private int minBaseline = 10;
    }
}

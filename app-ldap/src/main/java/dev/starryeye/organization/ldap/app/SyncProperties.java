package dev.starryeye.organization.ldap.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("sync")
public class SyncProperties {

    private String cron = "0 0 3 * * *";
    private String purgeCron = "0 0 4 * * *";
    private DeletionGuardConfig deletionGuard = new DeletionGuardConfig();

    @Getter
    @Setter
    public static class DeletionGuardConfig {
        private boolean enabled = true;
        private double thresholdRatio = 0.3;
        private int minBaseline = 10;
    }
}

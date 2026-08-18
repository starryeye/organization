package dev.starryeye.organization.scim.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("sync")
public class ArchiveProperties {

    private String archiveCron = "0 0 3 * * *";
    private String purgeCron = "0 0 4 * * *";
}

package dev.starryeye.organization.scim.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.starryeye.organization")
public class ScimSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScimSyncApplication.class, args);
    }
}

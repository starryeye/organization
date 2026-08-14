package dev.starryeye.organization.ldap.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.starryeye.organization")
public class LdapSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(LdapSyncApplication.class, args);
    }
}

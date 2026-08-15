package dev.starryeye.organization.authz;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenFgaProperties.class)
public class OpenFgaConfig {

    @Bean
    public StoreBootstrapper storeBootstrapper(OpenFgaProperties properties) {
        return new StoreBootstrapper(properties);
    }

    @Bean
    public OpenFgaStoreInitializer openFgaStoreInitializer(StoreBootstrapper bootstrapper) {
        return new OpenFgaStoreInitializer(bootstrapper);
    }

    @Bean
    public OpenFgaRelationTupleWriter openFgaRelationTupleWriter(
            StoreBootstrapper bootstrapper, OpenFgaProperties properties) {
        return new OpenFgaRelationTupleWriter(bootstrapper, properties);
    }
}

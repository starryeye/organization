package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.port.RelationTupleChecker;
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

    @Bean
    public RelationTupleChecker relationTupleChecker(StoreBootstrapper bootstrapper) {
        return new OpenFgaRelationTupleChecker(bootstrapper);
    }
}

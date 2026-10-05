package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;
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
    public RelationTupleChecker relationTupleChecker(StoreBootstrapper bootstrapper, OpenFgaProperties properties) {
        return new OpenFgaRelationTupleChecker(bootstrapper, properties.getRequestConcurrency());
    }

    /** 재적재의 장부 훑기. Read API 를 쓰는 유일한 빈이다 — 판단·쓰기 경로에 주입하지 않는다. */
    @Bean
    public RelationTupleScanner relationTupleScanner(StoreBootstrapper bootstrapper, OpenFgaProperties properties) {
        return new OpenFgaRelationTupleScanner(bootstrapper, properties);
    }

    /** OpenFGA SDK 예외가 일시 장애인지 알아본다 — 앱의 오류 분류기가 모아서 쓴다(설계 2026-10-05 §3.3). */
    @Bean
    public TemporaryFailureRecognizer openFgaTemporaryFailures() {
        return new OpenFgaTemporaryFailures();
    }
}

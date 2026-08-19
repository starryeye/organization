package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminQueryConfig {

    @Bean
    public AdminQueryUseCase adminQueryUseCase(DirectoryStateRepository state,
                                               DirectorySearchRepository search,
                                               RelationTupleChecker checker) {
        return new AdminQueryUseCase(state, search, checker);
    }

    @Bean
    public AdminQueryMetrics adminQueryMetrics(MeterRegistry registry) {
        return new AdminQueryMetrics(registry);
    }

    @Bean
    public AdminQueryController adminQueryController(AdminQueryUseCase useCase,
                                                     AdminQueryMetrics metrics) {
        return new AdminQueryController(useCase, metrics);
    }
}

package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code AdminQueryController} 는 {@code @RestController} 라 컴포넌트 스캔으로 등록된다
 * ({@code AdminSyncController} 와 같은 방식) — 여기서 {@code @Bean} 으로 다시 등록하지 않는다.
 * 두 스프링부트 앱 모두 {@code scanBasePackages = "dev.starryeye.organization"} 이라 스캔이
 * {@code dev.starryeye.organization.admin} 까지 닿는데, 같은 빈을 스캔과 {@code @Bean} 양쪽으로
 * 등록하면 {@code allowBeanDefinitionOverriding=false} 기본값에서 컨텍스트 기동이
 * {@code BeanDefinitionOverrideException} 으로 깨진다. {@code AdminQueryUseCase} 와
 * {@code AdminQueryMetrics} 는 스테레오타입 애노테이션이 없는 평범한 클래스라 {@code @Bean} 으로
 * 등록해야 하고({@code ScimConfig} 가 {@code ScimUserHandler}/{@code ScimGroupHandler} 를
 * 등록하는 것과 같은 이유), 그래서 충돌하지 않는다.
 */
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
}

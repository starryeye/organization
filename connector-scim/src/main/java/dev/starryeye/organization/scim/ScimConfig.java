package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ScimConfig {

    @Bean
    public ScimUserHandler scimUserHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync) {
        return new ScimUserHandler(state, sync);
    }

    @Bean
    public MemberTypeResolver memberTypeResolver(DirectoryStateRepository state) {
        return new StateMemberTypeResolver(state);
    }

    @Bean
    public ScimGroupHandler scimGroupHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync,
                                             MemberTypeResolver memberTypes) {
        return new ScimGroupHandler(state, sync, memberTypes);
    }

    @Bean
    public RouterFunction<ServerResponse> scimRouterFunction(ScimUserHandler users, ScimGroupHandler groups) {
        return ScimRouter.scimRoutes(users, groups);
    }
}

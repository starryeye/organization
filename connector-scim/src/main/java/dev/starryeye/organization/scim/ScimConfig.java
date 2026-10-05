package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ScimConfig {

    /** 받아서 버린 속성을 알릴 관찰자가 있으면 단다 — app-scim 의 메트릭(설계 2026-10-06 §3.3). 없으면 아무 일도 하지 않는다. */
    @Bean
    public ScimUserHandler scimUserHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync,
                                           ObjectProvider<IgnoredAttributeObserver> ignoredAttributes) {
        return new ScimUserHandler(state, sync, ignoredAttributes.getIfAvailable(() -> IgnoredAttributeObserver.NOOP));
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
    public ScimUserListing scimUserListing(DirectoryStateRepository state, DirectoryQueryRepository query,
                                           PageBookmarkRepository bookmarks) {
        return new ScimUserListing(state, query, bookmarks);
    }

    @Bean
    public ScimGroupListing scimGroupListing(DirectoryStateRepository state, DirectoryQueryRepository query,
                                             PageBookmarkRepository bookmarks) {
        return new ScimGroupListing(state, query, bookmarks);
    }

    @Bean
    public ScimListHandler scimListHandler(ScimUserListing users, ScimGroupListing groups) {
        return new ScimListHandler(users, groups);
    }

    /**
     * 어댑터가 낸 {@link TemporaryFailureRecognizer} 를 모두 모아 분류기를 만든다(없으면 core 표지와 I/O 실패만 본다).
     * 본문 한도는 코덱이 쓰는 {@code spring.codec.max-in-memory-size} 와 같은 값을 읽어 413 문구에 싣는다.
     */
    @Bean
    public RouterFunction<ServerResponse> scimRouterFunction(ScimUserHandler users, ScimGroupHandler groups,
                                                             ScimListHandler lists,
                                                             ObjectProvider<TemporaryFailureRecognizer> recognizers,
                                                             @Value("${spring.codec.max-in-memory-size:256KB}") DataSize 본문_한도) {
        return ScimRouter.scimRoutes(users, groups, lists,
                new TemporaryFailureClassifier(recognizers.orderedStream().toList()), 본문_한도.toBytes());
    }
}

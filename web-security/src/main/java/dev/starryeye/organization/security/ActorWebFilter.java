package dev.starryeye.organization.security;

import dev.starryeye.organization.core.audit.Actor;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.function.Function;

/**
 * 인증된 요청의 행위자를 Reactor Context 에 싣는다(설계 2026-10-28 §5.2, A9). 보안 체인의 인증 필터 뒤에 둔다
 * ({@link ResourceServerSecurityConfig}) — 그 자리면 보안 Context 에 인증이 있다. 토큰의 {@code sub} 로 {@code client:<sub>}({@link Actor#client})를
 * {@link Actor#KEY} 에 싣고, 인증이 없으면(프로브) 그대로 넘긴다.
 *
 * <p><b>하류 전체를 감싼다.</b> {@code contextWrite} 는 구독 체인의 위쪽에만 닿으므로 {@code chain.filter(exchange).contextWrite(…)} 로 뒤의 필터·처리기
 * 전부를 감싼다. 처리기 안에서 쓰면 감사를 남기는 분리 지점({@code IncrementalSyncUseCase.withLock}, {@code SyncJobs.띄운다})이 잡는 요청 Context 에 닿지 않는다.
 */
final class ActorWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .map(인증 -> 행위자를_싣는다(Actor.client(인증.getName())))
                .defaultIfEmpty(Function.identity())
                .flatMap(싣기 -> chain.filter(exchange).contextWrite(싣기));
    }

    private static Function<Context, Context> 행위자를_싣는다(String 행위자) {
        return context -> context.put(Actor.KEY, 행위자);
    }
}

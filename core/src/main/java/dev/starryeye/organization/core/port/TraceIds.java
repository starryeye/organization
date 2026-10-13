package dev.starryeye.organization.core.port;

import reactor.util.context.ContextView;

/**
 * 요청의 traceId — SCIM 쓰기 이력의 원인 id 다(설계 2026-10-10 §4.2). 추적은 앱의 일이라 core 는 요청의 Reactor Context 만 넘긴다.
 */
@FunctionalInterface
public interface TraceIds {

    /** 이 Context 의 traceId. 없으면 null. */
    String traceIdOf(ContextView context);

    TraceIds NONE = context -> null;
}

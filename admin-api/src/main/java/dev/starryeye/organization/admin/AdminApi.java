package dev.starryeye.organization.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 관리 API 컨트롤러 표지(설계 2026-10-27 §10). {@link AdminExceptionHandler} 가 이 표지를 단 컨트롤러에만 걸린다 —
 * SCIM 라우터와 actuator 는 건드리지 않는다. 지금 네 곳이다: {@code AdminQueryController}, {@code AuditHistoryController},
 * 두 앱의 {@code AdminSyncController}. 새 관리 컨트롤러를 더하면 여기에 이 표지를 달아야 같은 오류 응답을 낸다.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminApi {
}

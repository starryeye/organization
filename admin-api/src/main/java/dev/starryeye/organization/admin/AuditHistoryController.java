package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.AuditLog;
import dev.starryeye.organization.core.query.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 변경 이력 조회(설계 2026-10-10 §5). 대상 하나의 이력을 최신순으로 한 쪽씩 돌려준다. admin-api 라 LDAP·SCIM 배포 둘 다에 들어간다.
 *
 * <ul>
 *   <li>기록이 없는 아이디는 404 가 아니라 빈 목록이다 — 지워진 직원·조직도 보관 기간({@code dynamodb.audit-retention-days}, 기본 30일)
 *       동안은 이력이 보인다.</li>
 *   <li>커서는 불투명하다. 이 대상의 커서가 아니거나 망가졌으면 400 이다.</li>
 *   <li>다음 쪽 커서는 이번 쪽이 {@code limit} 줄로 찼을 때 준다 — 마지막 쪽 뒤에 빈 쪽이 하나 올 수 있다.</li>
 * </ul>
 *
 * <p>인증이 없다 — 지금 관리 API 와 같은 노출 수준이다(설계 §10). 이력에는 이전 값(이름·이메일 등)이 담긴다.
 */
@AdminApi
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AuditHistoryController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AuditLog auditLog;

    @GetMapping("/employees/{employeeId}/history")
    public Mono<Page<AuditHistoryItem>> employeeHistory(@PathVariable String employeeId,
                                                        @RequestParam(required = false) String cursor,
                                                        @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return history(MemberRef.user(employeeId), cursor, limit);
    }

    @GetMapping("/organizations/{orgCode}/history")
    public Mono<Page<AuditHistoryItem>> organizationHistory(@PathVariable String orgCode,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return history(MemberRef.group(orgCode), cursor, limit);
    }

    private Mono<Page<AuditHistoryItem>> history(MemberRef subject, String cursor, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 은 1 이상 " + MAX_LIMIT + " 이하여야 한다: " + limit));
        }
        return auditLog.history(subject, cursor, limit)
                .map(page -> new Page<>(page.items().stream().map(AuditHistoryItem::of).toList(), page.nextCursor()))
                .onErrorMap(IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error));
    }
}

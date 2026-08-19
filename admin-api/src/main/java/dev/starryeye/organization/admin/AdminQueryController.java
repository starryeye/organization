package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.query.EmployeeDetail;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.OrgMember;
import dev.starryeye.organization.core.query.OrganizationDetail;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
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
 * 관리자 조회. 기존 {@code /admin/sync} 와 같은 표면이 되도록 {@code @RestController} 와
 * {@link ResponseStatusException} 을 쓴다.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminQueryController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AdminQueryUseCase useCase;
    private final AdminQueryMetrics metrics;

    @GetMapping("/employees")
    public Mono<Page<UserSummary>> searchEmployees(
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

        int size = validLimit(limit);
        if (present(userName) == present(displayName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userName 과 displayName 중 정확히 하나를 지정해야 한다");
        }
        Mono<Page<UserSummary>> result = present(userName)
                ? useCase.searchEmployeesByUserName(userName, cursor, size)
                : useCase.searchEmployeesByDisplayName(displayName, cursor, size);
        return result.onErrorMap(IllegalArgumentException.class, this::badRequest);
    }

    @GetMapping("/employees/{employeeId}")
    public Mono<EmployeeDetail> employee(@PathVariable String employeeId) {
        return useCase.employeeDetail(employeeId)
                .doOnNext(metrics::recordEmployeeDetail)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "직원을 찾을 수 없다: " + employeeId)));
    }

    @GetMapping("/organizations")
    public Mono<Page<GroupSummary>> searchOrganizations(
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

        int size = validLimit(limit);
        if (!present(displayName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName 이 필요하다");
        }
        return useCase.searchOrganizations(displayName, cursor, size)
                .onErrorMap(IllegalArgumentException.class, this::badRequest);
    }

    @GetMapping("/organizations/{orgCode}")
    public Mono<OrganizationDetail> organization(@PathVariable String orgCode) {
        return useCase.organizationDetail(orgCode, DEFAULT_LIMIT)
                .doOnNext(metrics::recordOrganizationDetail)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "조직을 찾을 수 없다: " + orgCode)));
    }

    @GetMapping("/organizations/{orgCode}/members")
    public Mono<Page<OrgMember>> members(@PathVariable String orgCode,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int size = validLimit(limit);
        return useCase.organizationMembers(orgCode, cursor, size)
                .doOnNext(metrics::recordMembers)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "조직을 찾을 수 없다: " + orgCode)))
                .onErrorMap(IllegalArgumentException.class, this::badRequest);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private int validLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 은 1 이상 " + MAX_LIMIT + " 이하여야 한다: " + limit);
        }
        return limit;
    }

    private ResponseStatusException badRequest(IllegalArgumentException e) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
}

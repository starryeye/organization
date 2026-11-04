package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.query.OrganizationLookup;
import dev.starryeye.organization.core.query.UserSummary;
import dev.starryeye.organization.core.usecase.DirectoryLookupUseCase;
import dev.starryeye.organization.core.usecase.DirectoryLookupUseCase.결과;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.function.IntFunction;

/**
 * 정확 조회 둘(설계 2026-10-28 §6) — 권한을 묻는 앱이 로그인 이름이나 IdP 의 식별자로 {@code employeeId} 와 {@code orgCode} 를 얻는다.
 * 하나면 200, 없으면 404, 여럿이면 409 다. 여럿일 때도 목록은 싣지 않는다 — 이 길은 {@code directory.lookup} 범위로 열리는데, 그 범위는 개인 정보를
 * 훑는 범위가 아니다.
 *
 * <p>{@code /employees/lookup}·{@code /organizations/lookup} 은 글자 그대로의 경로라 {@code /employees/{employeeId}}·{@code /organizations/{orgCode}}
 * 보다 먼저 잡힌다(스프링은 구체적인 패턴을 먼저 고른다). 보안 규칙도 이 두 경로만 {@code directory.lookup} 으로 연다.
 */
@AdminApi
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class DirectoryLookupController {

    private final DirectoryLookupUseCase useCase;

    @GetMapping("/employees/lookup")
    public Mono<UserSummary> lookupEmployee(@RequestParam(required = false) String userName,
                                            @RequestParam(required = false) String externalId) {
        if (present(userName) == present(externalId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userName·externalId 중 정확히 하나를 지정해야 한다");
        }
        return useCase.직원을_찾는다(present(userName) ? userName : null, present(externalId) ? externalId : null)
                .flatMap(판정 -> 응답으로(판정, "일치하는 직원이 없다", 수 -> 수 + "명이 일치한다 — externalId 로 찾는다"));
    }

    @GetMapping("/organizations/lookup")
    public Mono<OrganizationLookup> lookupOrganization(@RequestParam(required = false) String externalId) {
        if (!present(externalId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "externalId 를 지정해야 한다");
        }
        return useCase.조직을_찾는다(externalId)
                .flatMap(판정 -> 응답으로(판정, "일치하는 조직이 없다", 수 -> 수 + "개가 일치한다"));
    }

    /** 하나면 그 값, 없으면 404, 여럿이면 409 — 여럿의 문구에는 수만 싣는다. */
    private static <T> Mono<T> 응답으로(결과<T> 판정, String 없음_문구, IntFunction<String> 여럿_문구) {
        if (판정 instanceof 결과.하나<T> 하나) {
            return Mono.just(하나.값());
        }
        if (판정 instanceof 결과.여럿<T> 여럿) {
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, 여럿_문구.apply(여럿.수())));
        }
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, 없음_문구));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}

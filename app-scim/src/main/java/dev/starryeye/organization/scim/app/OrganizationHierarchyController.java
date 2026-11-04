package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.admin.AdminApi;
import dev.starryeye.organization.admin.RetryLaterStatusException;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.usecase.GroupGraphTooLargeException;
import dev.starryeye.organization.core.usecase.HierarchyRejectedException;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 운영자가 조직의 상위를 지정하고 없앤다(설계 2026-11-04 §3). IdP 가 조직을 평평하게만 보내 계층이 비는 곳을 메운다 — 지정한 연결은 IdP 의 멤버 목록과
 * 상관없이 남는다. 두 경로는 {@code admin.hierarchy} 범위가 지킨다(web-security 의 경로 규칙).
 *
 * <p>일은 모두 {@link IncrementalSyncUseCase} 가 한다 — SCIM 쓰기와 같은 락·Check 기준선·diff·감사다. 이 컨트롤러는 본문을 읽고 유스케이스의 결과·오류를
 * 응답으로 옮길 뿐이다.
 *
 * <p><b>락 경합은 409 가 아니라 503 이다.</b> 관리 API 의 다른 경로({@code AdminSyncController})는 락 경합을 409 로 내지만 그것은 작업을 시작하는 요청이다.
 * 이 요청은 쓰기라 409 는 순환이 쓰고, 락 경합은 SCIM 쓰기처럼 503 + {@code Retry-After} 로 낸다. 처리기는 락 경합을 409 로 거르므로(원인 사슬에 든
 * 것도 같다) 여기서 {@link RetryLaterStatusException} 으로 바꿔 던진다 — 직접 던진 락 경합도, 다른 예외가 원인으로 품은 것도.
 */
@Slf4j
@AdminApi
@RestController
@RequestMapping("/admin/organizations")
@RequiredArgsConstructor
public class OrganizationHierarchyController {

    private static final String 본문_오류 = "parentOrgCode 가 필요하다";

    private final IncrementalSyncUseCase useCase;

    /**
     * 상위를 지정한다. 이미 있으면 바꾼다 — 같은 상위로 다시 보내면 바뀐 것이 없고 이력도 없다.
     *
     * @param body {@code {"parentOrgCode": "<상위 조직 코드>"}}. 없거나 텍스트가 아니거나 공백뿐이면 유스케이스를 부르기 전에 400 이다
     */
    @PutMapping("/{orgCode}/parent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> assign(@PathVariable String orgCode, @RequestBody(required = false) JsonNode body) {
        String parentOrgCode = 상위_코드를_읽는다(body);
        return 응답으로_옮긴다(orgCode, useCase.assignParent(orgCode, parentOrgCode));
    }

    /** 지정을 없앤다. 지정이 없으면 아무것도 하지 않는다 — 대상 조직이 없을 때만 404 다. */
    @DeleteMapping("/{orgCode}/parent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> clear(@PathVariable String orgCode) {
        return 응답으로_옮긴다(orgCode, useCase.clearParent(orgCode));
    }

    private static String 상위_코드를_읽는다(JsonNode body) {
        JsonNode 칸 = body == null ? null : body.get("parentOrgCode");
        if (칸 == null || !칸.isTextual() || 칸.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 본문_오류);
        }
        return 칸.asText();
    }

    /** 거절 사유의 상태 — 사유가 늘면 컴파일러가 이 표를 고치라고 알린다 */
    private static HttpStatus 상태(HierarchyRejectedException.Reason 사유) {
        return switch (사유) {
            case PARENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CYCLE -> HttpStatus.CONFLICT;
        };
    }

    /** 유스케이스의 결과·오류를 응답으로 옮긴다. 표에 없는 오류는 그대로 둔다 — 처리기가 일시 장애는 503 으로, 나머지는 500 으로 낸다. */
    private Mono<Void> 응답으로_옮긴다(String orgCode, Mono<IncrementalSyncResult> 요청) {
        return 요청
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "조직이 없다: " + orgCode)))
                .onErrorMap(HierarchyRejectedException.class, e -> new ResponseStatusException(상태(e.reason()), e.getMessage(), e))
                // 같은 요청은 다시 보내도 늘 넘는다 — 영구 거절이라 400 이다
                .onErrorMap(GroupGraphTooLargeException.class, e ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
                // 락 경합은 직접 던진 것뿐 아니라 다른 예외가 원인으로 품은 것도 503 이다 — 처리기는 사슬의 락 경합을 409 로 거른다
                .onErrorMap(RetryLaterStatusException::락_경합이면_바꾼다)
                .flatMap(결과 -> {
                    if (결과.fullyApplied()) {
                        return Mono.<Void>empty();
                    }
                    // 지정은 저장하지 않았다 — 다시 보내면 Check 기준선이 남은 줄만 반영한다
                    log.warn("지정 요청의 튜플 반영이 일부 실패했다 — 503. 조직 {}", orgCode);
                    return Mono.<Void>error(new RetryLaterStatusException(
                            "일부 튜플 적용에 실패했다 — 잠시 뒤 다시 보낸다: " + orgCode, TemporaryFailureException.기본_대기, null));
                });
    }
}

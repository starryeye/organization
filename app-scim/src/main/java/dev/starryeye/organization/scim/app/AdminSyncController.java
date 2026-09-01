package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.ScimRebuildMode;
import dev.starryeye.organization.core.usecase.ScimRebuildUseCase;
import dev.starryeye.organization.storage.DynamoDbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SCIM 인스턴스의 관리 API. app-ldap 의 같은 이름 컨트롤러와 표면을 맞춘다.
 *
 * <p><b>{@code /full} 이 없다.</b> LDAP 은 언제든 다시 읽어올 수 있지만 SCIM 은 push 모델이라
 * "전체를 다시 달라"고 말할 상대가 없다. 그래서 재적재와 이력 조회만 제공한다.
 */
@Slf4j
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    private static final int MIN_RUNS_LIMIT = 1;
    private static final int MAX_RUNS_LIMIT = 100;

    private final ScimRebuildUseCase rebuild;
    private final SyncRunRepository runs;
    private final DynamoDbProperties dynamoDb;

    /**
     * @param mode    {@code tuples}(기본) 또는 {@code wipe}
     * @param confirm {@code wipe} 일 때만 필요하다. DynamoDB 테이블명을 그대로 적어야 한다
     */
    @PostMapping("/rebuild")
    public Mono<SyncRunResponse> rebuild(@RequestParam(defaultValue = "tuples") String mode,
                                         @RequestParam(required = false) String confirm) {
        ScimRebuildMode rebuildMode;
        try {
            rebuildMode = ScimRebuildMode.from(mode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 mode 값: " + mode, e);
        }

        if (rebuildMode == ScimRebuildMode.WIPE) {
            requireConfirmation(confirm);
            log.warn("SCIM 조직도 전체 초기화 요청. 실행 뒤 IdP 재프로비저닝이 필요하다");
        } else {
            log.warn("SCIM 튜플 재적재 요청");
        }

        return rebuild.execute(rebuildMode)
                .map(SyncRunResponse::from)
                // 다른 인스턴스가 SCIM 쓰기나 재적재로 락을 쥐고 있어 이번 재적재가 시작하지
                // 못한 경우는 409 다 — 관리자가 잠시 뒤 다시 시도하면 된다.
                .onErrorMap(LockUnavailableException.class, e ->
                        new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }

    /**
     * 조직도의 유일한 사본을 지우는 요청이라 불리언 플래그로는 부족하다. 테이블명을 적게 하면
     * 관리자가 <b>자기가 무엇을 지우는지 찾아보게</b> 된다 — 손가락이 미끄러져 눌리지 않는다.
     */
    private void requireConfirmation(String confirm) {
        if (!dynamoDb.getTableName().equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "wipe 는 confirm 에 DynamoDB 테이블명을 그대로 적어야 합니다. "
                            + "이 작업은 조직도를 전부 지우며 되돌릴 수 없고, 실행 뒤 IdP 콘솔에서 "
                            + "전체 재프로비저닝을 걸어야 복구됩니다");
        }
    }

    @GetMapping("/runs")
    public Flux<SyncRunResponse> runs(@RequestParam(defaultValue = "20") int limit) {
        int clamped = Math.max(MIN_RUNS_LIMIT, Math.min(limit, MAX_RUNS_LIMIT));
        return runs.findRecent(clamped).map(SyncRunResponse::from);
    }
}

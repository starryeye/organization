package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;
import java.util.List;

/**
 * 앱이 뜰 때, 지난 프로세스가 끝내지 못한 app-ldap 실행 기록(RUNNING)을 FAILED("재시작으로 중단")로 닫는다 (설계 2026-09-29 §4).
 *
 * <p>실행 가드가 프로세스 안에만 있어, 이 프로세스가 뜨는 시점에 살아 있는 app-ldap 작업은 없다 — 남은 RUNNING 은 전부 죽은
 * 프로세스(kill -9, 정전, 정상 종료 대기 초과)의 것이다. 두면 영원히 RUNNING 으로 보여 "지금 도는 중인가"를 기록으로 판단할 수 없다.
 *
 * <p>빈 생성 단계에서 돈다 — 웹 서버가 요청을 받기 전, 스케줄러가 돌기 전이라 이 프로세스가 연 기록을 잘못 닫지 않는다. 실패해도 앱
 * 시작을 막지 않는다(경고만 남긴다) — 기록 정리는 부가 기능이다.
 *
 * <p>app-ldap 을 여러 대 띄우면 이 전제가 깨진다 — 점검 ②-2(M14 클러스터 락)에서 다시 본다.
 */
@Slf4j
@RequiredArgsConstructor
public class InterruptedRunCleanup implements InitializingBean {

    static final String 사유 = "재시작으로 중단";

    /** 최근 기록 몇 개를 볼지. 죽은 프로세스의 RUNNING 은 가장 최근 기록들 사이에 있다. */
    private static final int 살펴볼_기록 = 100;

    private final SyncRunRepository runs;

    @Override
    public void afterPropertiesSet() {
        try {
            List<SyncRun> 닫은것 = runs.findRecent(살펴볼_기록)
                    .filter(run -> run.source() == SyncSource.LDAP && run.status() == SyncStatus.RUNNING)
                    .concatMap(run -> runs.finish(run, SyncOutcome.failed(사유)))
                    .collectList()
                    .block(Duration.ofSeconds(30));
            if (닫은것 != null && !닫은것.isEmpty()) {
                log.warn("지난 프로세스가 끝내지 못한 실행 기록 {}개를 FAILED(\"{}\")로 닫았다: {}",
                        닫은것.size(), 사유, 닫은것.stream().map(SyncRun::runId).toList());
            }
        } catch (RuntimeException e) {
            log.warn("시작 시점에 남은 실행 기록을 정리하지 못했다. 앱은 계속 기동한다", e);
        }
    }
}

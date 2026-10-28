package dev.starryeye.organization.ldap.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 동기화 설정. {@code UseCaseConfig#syncProperties} 가 빈 이름 {@code syncProperties} 로 올리고, {@code SyncScheduler} 의
 * {@code @Scheduled(cron = "#{@syncProperties.cron}")} 가 SpEL 로 그 값을 읽는다(설계 2026-10-27 §5). 그래서 cron 기본값의 출처는 이 클래스 하나이고
 * {@code application.yml} 은 덮어쓸 때만 쓴다 — 예전의 {@code ${sync.cron}} 자리표시자는 yml 에 같은 값을 따로 적어야 했다. cron 에 {@code "-"} 를 주면
 * 그 작업이 꺼진다(스프링 {@code @Scheduled} 의 약속).
 */
@Getter
@Setter
@ConfigurationProperties("sync")
public class SyncProperties {

    /**
     * 매시 정각(설계 2026-10-20 §8). 하루 한 번이면 퇴사 회수가 최악 하루 늦는다. 퇴사 회수는 다음 정각 + 회차 시간에 닿는다. 변경 없는 회차는 쓰기가 0번이지만
     * 디렉터리 전체와 직전 스냅샷, 현재상태의 모든 직원·조직(META)과 조직마다 멤버 한 Query 를 읽는다. 변경 있는 회차는 스냅샷 한 벌을 통째로 새로 쓴다(10만 명
     * 약 6MB·묶음 18개). 삭제 가드가 훑어 맞추기를 통째로 막는 동안은 매 회차 D 전체를 다시 쓰고 장부를 훑는다. README "회수 목표" 에 회차별 비용이 있다.
     */
    private String cron = "0 0 * * * *";

    /**
     * 만료 스냅샷 정리. 스냅샷은 테이블 TTL 을 쓰지 않으므로 이 정리가 보존 기간이 지난 스냅샷을 지우는 유일한 경로다. {@code "-"} 로 끄면 스냅샷(각 약 10만
     * 아이템)이 끝없이 쌓인다.
     *
     * <p><b>하루 창(04~08시) 안에서 매시 시도한다(설계 2026-10-27 §9).</b> 하루 1회 표지({@code ldap-purge})를 잡은 인스턴스만 돌고, 실패하면 표지를 풀어
     * 다음 정각에 다시 돈다. 성공한 뒤의 시도는 표지를 보고 건너뛴다. 표지의 날짜는 UTC 이므로 <b>창이 UTC 자정을 넘지 않아야</b> 한 창이 한 날로 묶인다 —
     * 기본 창은 서버 시간대가 UTC 든 KST 든 이것을 지킨다(KST 08시는 UTC 23시). 다른 시간대에서 창을 바꿀 때 이 조건을 지킨다. 어겨도 정확성은 깨지지 않고
     * (정리는 멱등) 창이 두 날로 갈려 하루에 두 번 돌 수 있을 뿐이다.
     */
    private String purgeCron = "0 0 4-8 * * *";
    private DeletionGuardConfig deletionGuard = new DeletionGuardConfig();

    /**
     * 동기화·재적재 한 번의 전체 기한(설계 2026-09-29 §5). 넘으면 남은 일을 멈추고 FAILED("기한 초과 — N분")로 기록한 뒤 가드를
     * 푼다. 10만 명 재적재(쓰기 약 1,100 배치 + 장부 훑기 약 1,100 호출)를 수 분으로 추정해 넉넉히 잡았다. 하루 1회 표지의 임대는 잡은 때 + 이 기한 + 5분이다
     * (설계 2026-10-27 §9).
     */
    private Duration jobTimeout = Duration.ofMinutes(30);

    @Getter
    @Setter
    public static class DeletionGuardConfig {
        private boolean enabled = true;
        private double thresholdRatio = 0.3;
        private int minBaseline = 10;
    }
}

package dev.starryeye.organization.ldap.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("sync")
public class SyncProperties {

    /**
     * 매시 정각(설계 2026-10-20 §8). 하루 한 번이면 퇴사 회수가 최악 하루 늦는다. 변경 없는 회차는 쓰기가 0번이지만 디렉터리 전체와 직전 스냅샷, 현재상태의
     * 모든 직원·조직(META)과 조직마다 멤버 한 Query 를 읽는다. 변경 있는 회차는 스냅샷 한 벌을 통째로 새로 쓴다(10만 명 약 6MB·묶음 18개). 삭제 가드가 훑어 맞추기를
     * 통째로 막는 동안은 매 회차 D 전체를 다시 쓰고 장부를 훑는다. README "회수 목표" 에 회차별 비용이 있다.
     */
    private String cron = "0 0 * * * *";
    private String purgeCron = "0 0 4 * * *";
    private DeletionGuardConfig deletionGuard = new DeletionGuardConfig();

    /**
     * 동기화·재적재 한 번의 전체 기한(설계 2026-09-29 §5). 넘으면 남은 일을 멈추고 FAILED("기한 초과 — N분")로 기록한 뒤 가드를
     * 푼다. 10만 명 재적재(쓰기 약 1,100 배치 + 장부 훑기 약 1,100 호출)를 수 분으로 추정해 넉넉히 잡았다.
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
